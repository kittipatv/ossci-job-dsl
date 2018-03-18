import javaposse.jobdsl.dsl.helpers.step.MultiJobStepContext
import ossci.pytorch.Users

def dockerImages = [
  "pytorch-linux-trusty-py2.7.9",
  "pytorch-linux-trusty-py2.7",
  "pytorch-linux-trusty-py3.5",
  "pytorch-linux-trusty-py3.6-gcc5.4",
  "pytorch-linux-trusty-py3.6-gcc4.8",
  "pytorch-linux-trusty-py3.6-gcc7.2",
  "pytorch-linux-trusty-pynightly",
  "pytorch-linux-xenial-cuda8-cudnn6-py2",
  "pytorch-linux-xenial-cuda8-cudnn6-py3",
  "pytorch-linux-xenial-cuda9-cudnn7-py2",
  "pytorch-linux-xenial-cuda9-cudnn7-py3",
  "pytorch-linux-xenial-py3-clang5-asan",
  // "pytorch-linux-artful-cuda9-cudnn7-py3",
]

def dockerBasePath = 'pytorch-docker'

// Put all the Docker image building related jobs in a single folder
folder(dockerBasePath) {
  description 'Jobs concerning building Docker images for PyTorch builds'
}

multiJob("${dockerBasePath}-master") {
  parameters {
    stringParam(
      'sha1',
      'origin/master',
      'Refspec of commit to use (e.g. origin/master)',
    )
  }
  label('simple')
  scm {
    git {
      remote {
        github('pietern/pytorch-dockerfiles')
        refspec([
            // Fetch all branches
            '+refs/heads/*:refs/remotes/origin/*',
            // Fetch PRs so we can trigger from PRs
            '+refs/pull/*:refs/remotes/origin/pr/*',
          ].join(' '))
      }
      branch('${sha1}')
    }
  }
  triggers {
    // Pushes trigger builds
    githubPush()
    // We also refresh the docker image on a weekly basis
    cron('@weekly')
  }
  steps {
    phase("Build images") {
      dockerImages.each {
        phaseJob("${dockerBasePath}/${it}") {
          parameters {
            predefinedProp('UPSTREAM_BUILD_ID', '${BUILD_ID}')
            gitRevision()
          }
        }
      }
    }

    phase("Test PyTorch master against new images") {
      phaseJob("pytorch-master") {
        parameters {
          predefinedProp('DOCKER_IMAGE_TAG', '${BUILD_ID}')
          booleanParam('RUN_DOCKER_ONLY', true)
        }
      }
    }
    phase("Deploy new images") {
      phaseJob("${dockerBasePath}/deploy") {
        parameters {
          predefinedProp('DOCKER_IMAGE_TAG', '${BUILD_ID}')
        }
      }
    }
  }
  publishers {
    postBuildScripts {
      steps {
      }
      onlyIfBuildFails()
    }
  }
}

// This job cannot trigger
job("${dockerBasePath}-pull-request") {
  label('simple')
  scm {
    git {
      remote {
        github('pietern/pytorch-dockerfiles')
        refspec('+refs/pull/*:refs/remotes/origin/pr/*')
      }
      branch('${sha1}')
    }
  }
  triggers {
    githubPullRequest {
      admins(Users.githubAdmins)
      userWhitelist(Users.githubUserWhitelist)
      useGitHubHooks()
    }
  }
  steps {
    downstreamParameterized {
      trigger("${dockerBasePath}/trigger") {
        block {
          buildStepFailure('FAILURE')
        }
        parameters {
          gitRevision()
        }
      }
    }
  }
}

dockerImages.each {
  // Capture variable for delayed evaluation
  def buildEnvironment = it

  job("${dockerBasePath}/${buildEnvironment}") {
    parameters {
      stringParam(
        'sha1',
        '',
        'Refspec of commit to use (e.g. origin/master)',
      )
      stringParam(
        'UPSTREAM_BUILD_ID',
        '',
        'Upstream build ID to tag with',
      )
    }

    wrappers {
      timestamps()

      credentialsBinding {
        usernamePassword('USERNAME', 'PASSWORD', 'nexus-jenkins')
      }
    }

    label('docker && cpu')

    scm {
      git {
        remote {
          github('pietern/pytorch-dockerfiles')
          refspec([
              // Fetch all branches
              '+refs/heads/*:refs/remotes/origin/*',
              // Fetch PRs so we can trigger from PRs
              '+refs/pull/*:refs/remotes/origin/pr/*',
            ].join(' '))
        }
        branch('${sha1}')
      }
    }

    steps {
      // Note: uses UPSTREAM_BUILD_ID to make sure all images
      // are tagged with the same number, even if the build numbers
      // of the individual builds are different.
      shell '''#!/bin/bash

set -ex

retry () {
    $*  || (sleep 1 && $*) || (sleep 2 && $*)
}

# If UPSTREAM_BUILD_ID is set (see trigger job), then we can
# use it to tag this build with the same ID used to tag all other
# base image builds. Also, we can try and pull the previous
# image first, to avoid rebuilding layers that haven't changed.
if [ -z "${UPSTREAM_BUILD_ID}" ]; then
  tag="adhoc-${BUILD_ID}"
else
  last_tag="$((UPSTREAM_BUILD_ID - 1))"
  tag="${UPSTREAM_BUILD_ID}"
fi

image="registry.pytorch.org/pytorch/${JOB_BASE_NAME}"

login() {
  echo "${PASSWORD}" | docker login -u "${USERNAME}"  --password-stdin registry.pytorch.org
}

# Login to registry.pytorch.org (the credentials plugin masks these values).
# Retry on timeouts (can happen on job stampede).
retry login

# Logout on exit
trap 'docker logout registry.pytorch.org' EXIT

export EC2=1
export JENKINS=1

# Try to pull the previous image (perhaps we can reuse some layers)
if [ -n "${last_tag}" ]; then
  docker pull "${image}:${last_tag}" || true
fi

# Build new image
./build.sh ${JOB_BASE_NAME} -t "${image}:${tag}"

docker push "${image}:${tag}"
'''
    }
  }
}

job("${dockerBasePath}/deploy") {
  parameters {
    stringParam(
      'DOCKER_IMAGE_TAG',
      "",
      'Tag of Docker image to deploy',
    )
  }
  wrappers {
    timestamps()
  }
  label('simple')
  scm {
    git {
      remote {
        github('pietern/ossci-job-dsl', 'ssh')
        credentials('caffe2bot')
      }
      branch('origin/master')
    }
  }
  steps {
    shell '''#!/bin/bash

set -ex

if [ -z "${DOCKER_IMAGE_TAG}" ]; then
  echo "DOCKER_IMAGE_TAG not set; I don't know what docker image to deploy"
  exit 1
fi

cat > src/main/groovy/ossci/pytorch/DockerVersion.groovy <<EOL
// This file is automatically generated
package ossci.pytorch
class DockerVersion {
  static final String version = "${DOCKER_IMAGE_TAG}";
}
EOL
git add src/main/groovy/ossci/pytorch/DockerVersion.groovy
git commit -m "Update PyTorch DockerVersion"
'''
  }
  publishers {
    git {
      pushOnlyIfSuccess()
      branch('origin', 'master')
    }
  }
}