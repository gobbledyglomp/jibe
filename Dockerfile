FROM python:3.13-slim

LABEL org.opencontainers.image.title="Jibe"
LABEL org.opencontainers.image.description="Seamless Android and Linux integration over LAN"
LABEL org.opencontainers.image.licenses="GPL-3.0"

# System packages required by Python deps (cryptography, pystray, dbus-python)
# xdotool/ydotool are optional for presentation remote — mount /dev/uinput to use them
RUN apt-get update && apt-get install -y --no-install-recommends \
    libdbus-1-dev \
    pkg-config \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY daemon/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY daemon/ .

EXPOSE 8765

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -sf http://localhost:8765/ || exit 1

# --no-tray: system tray requires a desktop session; Docker runs headless.
# Mount /dev/uinput and /run/user/1000/bus for presentation remote and notifications.
ENTRYPOINT ["python", "main.py", "--no-tray"]
