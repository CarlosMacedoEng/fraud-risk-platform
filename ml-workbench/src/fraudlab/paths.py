"""Repository-relative paths. The workbench container mounts the repository at /work."""
from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(os.environ.get("REPO_ROOT", Path(__file__).resolve().parents[3]))
DATA_DIR = REPO_ROOT / "data"
GENERATED_DIR = DATA_DIR / "generated"
SAMPLES_DIR = DATA_DIR / "samples"
MODELS_DIR = REPO_ROOT / "models"
REPORTS_DIR = REPO_ROOT / "ml-workbench" / "reports"


def dataset_dir(customer: str) -> Path:
    return GENERATED_DIR / customer
