package ossci.tensorcomp

class DockerImages {
  static final List<String> images = [
    // non-conda builds
    "linux-trusty-gcc4.8-cuda8-cudnn7-py3",
    "linux-xenial-gcc5-cuda9-cudnn7-py3-conda",

    // Conda builds
    "linux-trusty-gcc4.8-cuda8-cudnn7-py3-conda",
    "linux-xenial-gcc5-cuda9-cudnn7-py3",
  ];
}