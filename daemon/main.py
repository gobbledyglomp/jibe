"""Jibe daemon script entry point.

Run directly during development:

    python main.py                 # TLS enabled (default)
    python main.py --no-tls        # plaintext for development
    python main.py -p 9000         # custom port
    python main.py --regen-certs   # force regenerate TLS certificate
    python main.py -v              # debug logging
    python main.py --no-tray       # headless (no system tray)

When installed via pip/pipx, use the ``jibe`` console script instead.
Full CLI logic lives in jibe.cli.
"""

from jibe.cli import main

if __name__ == "__main__":
    main()
