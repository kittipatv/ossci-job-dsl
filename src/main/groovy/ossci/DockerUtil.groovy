package ossci

class DockerUtil {

  // Arguments:
  //  context
  //  image
  //  commitImage
  //  registryCredentials (optional)
  //  cudaVersion
  //  importEnv
  //  workspaceSource
  //    - host-mount: mount the host workspace at the Docker image workspace location
  //    - host-copy: copy the host workspace into the Docker image
  //    - docker: don't do anything, use the Docker workspace directly
  //  script
  static void shell(Map attrs) {
    String prefix = '''#!/bin/bash
#
# Helper to run snippet inside a Docker container.
#
# The Jenkins workspace is mounted at the same path, the working directory
# is the same, the environment is the same, and the user is the same.
#

set -ex

# Turn off CPU frequency scaling and GPU autoboost for GPU instances, so that we get consistent performance numbers
if [ -n "${CUDA_VERSION:-}" ]; then
    (sudo /usr/bin/set_gpu_autoboost_off.sh) || true
fi

retry () {
    $*  || (sleep 1 && $*) || (sleep 2 && $*) || (sleep 4 && $*) || (sleep 8 && $*)
}

case "$WORKSPACE_SOURCE" in
  host-mount)
    echo "Mounting host workspace into Docker image"
    ;;
  host-copy)
    echo "Copying host workspace into Docker image"
    ;;
  docker)
    echo "Using Docker image's workspace directly"
    ;;
  *)
    echo "Illegal WORKSPACE_SOURCE; valid values are host-mount, host-copy or docker"
    exit 0
    ;;
esac

output=/dev/stdout
# Uncomment this to be
# Non-verbose by default...
# output=/dev/null
# if [ -n "${DEBUG:-}" ]; then
#   set -x
#   output=/dev/stdout
# fi

if [ -z "${DOCKER_IMAGE:-}" ]; then
  echo "Please set the DOCKER_IMAGE environment variable..."
  exit 1
fi

export -p | sed -e '/ DOCKER_IMAGE=/d' -e '/ PWD=/d' > ./env

docker_args=""

# Needs pseudo-TTY for /bin/cat to hang around
docker_args+="-t"

# Detach so we can use docker exec to run stuff
docker_args+=" -d"

# Refer to shared ccache directory
docker_args+=" -v ccache:/var/lib/jenkins/.ccache"

# Mount the workspace to another location so we can copy files to it
docker_args+=" -v $WORKSPACE:/var/lib/jenkins/host-workspace"

# Prepare for capturing core dumps
mkdir -p $WORKSPACE/crash
docker_args+=" -v $WORKSPACE/crash:/var/crash"

if [ "$WORKSPACE_SOURCE" = "host-mount" ]; then
    # Directly mount host workspace into workspace directory.
    # This is "old-style" behavior
    docker_args+=" -v $WORKSPACE:/var/lib/jenkins/workspace"
fi

# Working directory is homedir
docker_args+=" -w /var/lib/jenkins"

if [ -n "${CUDA_VERSION:-}" ]; then
    # Extra arguments to use for nvidia-docker
    docker_args+=" --runtime=nvidia"

    # If CUDA_VERSION is equal to native, it it using one of the
    # nvidia/cuda images as base image and all CUDA related metadata
    # is embedded in the image itself.
    if [ "${CUDA_VERSION}" != "native" ]; then
      docker_args+=" -e CUDA_VERSION=${CUDA_VERSION}"
      docker_args+=" -e NVIDIA_VISIBLE_DEVICES=all"
    fi
fi

# Image
docker_args+=" ${DOCKER_IMAGE}"

# Sometimes, docker pull will fail with "TLS handshake timed out"
# or "unexpected EOF".  This usually indicates intermittent failure.
# Try again!
retry docker pull "${DOCKER_IMAGE}"

# We start a container and detach it such that we can run
# a series of commands without nuking the container
echo "Starting container for image ${DOCKER_IMAGE}"
id=$(docker run ${docker_args} /bin/cat)

trap "echo 'Stopping container...' &&
# Turn on CPU frequency scaling and GPU autoboost for GPU instances again
if [ -n \\"${CUDA_VERSION:-}\\" ]; then
    (sudo /usr/bin/set_gpu_autoboost_on.sh) || true;
fi &&
docker rm -f $id > /dev/null" EXIT

if [ "$WORKSPACE_SOURCE" = "host-copy" ]; then
    # Copy the workspace into the Docker image.
    # Pick this if you want the source code to persist into a saved
    # docker image
    docker cp $WORKSPACE/. "$id:/var/lib/jenkins/workspace"
fi

# Copy in the env file
docker cp $WORKSPACE/env "$id:/var/lib/jenkins/workspace/env"

# I found the only way to make the command below return the proper
# exit code is by splitting run and exec. Executing run directly
# doesn't propagate a non-zero exit code properly.
(
    # Get into working dir, now that it exists
    echo 'cd workspace'

    if [ "$IMPORT_ENV" == 1 ]; then
      # Source environment
      echo 'source ./env'
    fi

    # Override WORKSPACE environment variable. Every container build
    # uses /var/lib/jenkins/workspace for their $WORKSPACE and $PWD
    # instead of /var/lib/jenkins/workspace/some/build/name.
    # This should improve the ccache hit rate.
    echo 'declare -x WORKSPACE=$PWD'

    # Use everything below the '####' as script to run
    sed -n '/^####/ { s///; :a; n; p; ba; }' "${BASH_SOURCE[0]}"
) | docker exec -u jenkins -i "$id" bash

if [ -n "${COMMIT_DOCKER_IMAGE:-}" ]; then
    echo "Committing container state to ${COMMIT_DOCKER_IMAGE}..."
    docker commit "$id" "${COMMIT_DOCKER_IMAGE}" > "$output"
    retry docker push "${COMMIT_DOCKER_IMAGE}" > "$output"
    # There's no reason to keep this image around locally, so kill it
    docker rmi "${COMMIT_DOCKER_IMAGE}" > "$output"
fi

exit 0

#### SCRIPT TO RUN IN DOCKER CONTAINER BELOW THIS LINE
'''

    attrs.context.with {
      environmentVariables {
        env('DOCKER_IMAGE', attrs.image)
        env('COMMIT_DOCKER_IMAGE', attrs.getOrDefault("commitImage", ""))
        env('CUDA_VERSION', attrs.getOrDefault("cudaVersion", ""))
        // TODO: Consider using an enum here. Unfortunately, I don't know how to conveniently
        // ferry the result to java.
        env('WORKSPACE_SOURCE', attrs.getOrDefault("workspaceSource", "host-mount"))
        env('COPY_WORKSPACE', attrs.getOrDefault("copyWorkspace", ""))
        env('IMPORT_ENV', attrs.getOrDefault("importEnv", 1))
      }

      // Optionally login with registry before doing anything
      def credentials = attrs.get("registryCredentials")
      if (credentials != null) {
        def username = credentials[0]
        def password = credentials[1]
        def registry = attrs.image.split('/').first()

        shell """#!/bin/bash
echo "Logging into ${registry}"
retry () {
    \$*  || (sleep 1 && \$*) || (sleep 2 && \$*)
}
do_login () {
  echo ${password} | docker login -u ${username} --password-stdin ${registry}
}
retry do_login
"""
      }

      shell(prefix + attrs.script)
    }
  }
}