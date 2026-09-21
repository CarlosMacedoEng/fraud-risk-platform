# Immutable, versioned bundle of model artifacts + customer configuration, used as an init container in Kubernetes
# (deploy/k8s). The image tag is the bundle version; promoting a model or strategy set = changing one tag, and
# rolling back = the previous tag. Build from the repository root:
#   docker build -f deploy/docker/artifact-bundle.Dockerfile -t fraud-platform/artifact-bundle:1.1.0 .
FROM busybox:1.37
COPY models /bundle/models
COPY config /bundle/config
USER 999
