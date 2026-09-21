FROM python:3.12-slim
# libgomp is required by LightGBM
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
WORKDIR /work/ml-workbench
COPY requirements.lock /tmp/requirements.lock
RUN pip install --no-cache-dir -r /tmp/requirements.lock
ENV PYTHONPATH=/work/ml-workbench/src PYTHONHASHSEED=0 MPLBACKEND=Agg
CMD ["python", "-m", "fraudlab.cli", "--help"]
