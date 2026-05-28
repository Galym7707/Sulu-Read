FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV PYTHONIOENCODING=utf-8
ENV HF_HOME=/app/.cache/huggingface
ENV EASYOCR_MODULE_PATH=/app/.cache/easyocr
ENV SULU_READ_EASYOCR_GPU=false

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r /app/requirements.txt

COPY main.py /app/main.py
COPY backend /app/backend

RUN python -c "import easyocr; easyocr.Reader(['ru', 'rs_cyrillic', 'mn', 'en'], gpu=False, verbose=False)"

EXPOSE 7860

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860"]
