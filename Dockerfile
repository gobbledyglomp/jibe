FROM python:3.13-slim

LABEL org.opencontainers.image.title="Jibe"
LABEL org.opencontainers.image.description="Seamless Android and Linux integration over LAN"
LABEL org.opencontainers.image.licenses="GPL-3.0"

# curl is used only for the HEALTHCHECK probe; no build-time deps needed.
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install dependencies before copying source for layer-cache efficiency.
COPY daemon/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY daemon/ .

# Port 8776: WSS endpoint (Android WebSocket, TLS)
# Port 8777: plain-HTTP dashboard / REST API (localhost-only when TLS is active)
EXPOSE 8776 8777

# Give the daemon extra time on first start (TLS cert generation + DB init).
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -sf http://localhost:8777/health || exit 1

# System tray requires a desktop session and is disabled in Docker.
# To override port or TLS, set JIBE_PORT / JIBE_NO_TLS env vars in your compose file.
ENV JIBE_NO_TRAY=1
ENTRYPOINT ["python", "main.py"]
