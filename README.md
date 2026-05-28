---
title: Sulu Read Backend
colorFrom: green
colorTo: yellow
sdk: docker
app_port: 7860
pinned: false
license: mit
---

# Sulu-Read Backend

FastAPI backend for adapting textbook pages and article links into dyslexia-friendly syllable-separated text.

Available endpoints:

- `GET /health`
- `POST /v1/adapt-url`
- `POST /v1/adapt-image`
