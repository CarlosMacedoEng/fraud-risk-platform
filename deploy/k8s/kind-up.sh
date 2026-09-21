#!/usr/bin/env bash
# Deploy the dev overlay to a local kind cluster (verified on kind v0.30.0, Kubernetes node image of that release).
# Prerequisites: images built by docker compose (deploy/docker-compose.yml build) and kind on PATH.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLUSTER=${CLUSTER:-fraud-dev}
docker build -q -f "$ROOT/deploy/docker/artifact-bundle.Dockerfile" -t fraud-platform/artifact-bundle:1.1.0 "$ROOT"
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 120s
for img in fraud-platform-decision-service:latest fraud-platform-model-service:latest fraud-platform-file-adapter:latest \
           fraud-platform-simulators:latest fraud-platform/artifact-bundle:1.1.0; do
  kind load docker-image --name "$CLUSTER" "$img"      # public images (postgres, redis, kafka) are pulled by the node
done
kubectl apply -k "$ROOT/deploy/k8s/overlays/dev"
for d in postgres redis kafka simulators model-service decision-service file-adapter; do
  kubectl -n fraud-dev rollout status "deploy/$d" --timeout=360s
done
echo "Smoke test: kubectl -n fraud-dev run smoke --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- curl -s http://decision-service/actuator/health/readiness"
