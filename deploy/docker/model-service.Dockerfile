FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 curl && rm -rf /var/lib/apt/lists/*
WORKDIR /srv
# Minimal runtime dependencies (the full workbench image also contains training/plotting libraries).
RUN pip install --no-cache-dir fastapi==0.136.1 uvicorn==0.46.0 lightgbm==4.7.0 onnxruntime==1.30.0 numpy==2.5.3 pandas==3.0.6 \
    matplotlib scikit-learn networkx
COPY ml-workbench/src/fraudlab/__init__.py ml-workbench/src/fraudlab/reasons.py /srv/src/fraudlab/
COPY ml-workbench/model_service /srv/model_service
ENV PYTHONPATH=/srv/src:/srv MODELS_DIR=/models
RUN useradd --system app
USER app
EXPOSE 8000
CMD ["uvicorn", "model_service.app:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
