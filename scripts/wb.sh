#!/usr/bin/env bash
# Run a workbench command inside the pinned Python image, with the repository mounted at /work.
# Usage: scripts/wb.sh python -m fraudlab.cli generate --customer aldermoor-bank
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
exec docker run --rm ${WB_DOCKER_ARGS:-} -e REPO_ROOT=/work -v "$ROOT":/work -w /work/ml-workbench fraudlab-workbench:1.0 "$@"
