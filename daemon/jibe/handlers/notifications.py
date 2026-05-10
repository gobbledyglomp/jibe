"""Mirror Android notifications to the Linux desktop via ``notify-send``.

Incoming ``notification`` messages may carry:
- ``app_name``  — human-readable application label (falls back to ``app`` package name).
- ``icon``      — base64 PNG of the app's small icon (``--icon``, desktop chrome).
- ``image``     — base64 PNG of the notification's large icon / picture.

Some desktops (notably KDE Plasma) collapse ``--icon`` and ``image-path`` into one slot when
both are present. When **both** icon and image arrive, we composite them horizontally into a
single PNG passed only via ``string:image-path``, while still supplying ``--icon`` from the raw
app icon for desktops that honour both hints independently.

Temporary PNG files are written to the OS temp directory and deleted after ``notify-send``
finishes, regardless of success or failure.
"""

from __future__ import annotations

import asyncio
import base64
import binascii
import logging
import os
import tempfile
from io import BytesIO

from jibe.core.api import JibeMessage, format_error
from jibe.network.connection import JibeConnection

logger = logging.getLogger(__name__)

try:
    from PIL import Image
except ImportError:  # pragma: no cover — Pillow is a declared dependency
    Image = None


def _decode_b64_field(value: object, label: str) -> bytes | None:
    """Decode an optional base64 string field; log and return None on bad data."""
    if not isinstance(value, str) or not value:
        return None
    try:
        return base64.b64decode(value)
    except (binascii.Error, ValueError):
        logger.debug("Ignoring invalid %s base64 in notification payload", label)
        return None


def _write_temp_png(data: bytes, prefix: str) -> str:
    """Write *data* to a named temp file with a ``.png`` suffix; return its path."""
    fd, path = tempfile.mkstemp(suffix=".png", prefix=f"jibe_{prefix}_")
    try:
        os.write(fd, data)
    finally:
        os.close(fd)
    return path


def _unlink_silently(path: str | None) -> None:
    if path is None:
        return
    try:
        os.unlink(path)
    except FileNotFoundError:
        pass


def compose_icon_and_thumbnail_png(icon_png: bytes, picture_png: bytes) -> bytes | None:
    """Merge app icon (left) and notification image (right) into one PNG.

    Returns PNG bytes, or ``None`` if Pillow fails or is unavailable.
    """
    if Image is None:
        return None
    try:
        icon_im = Image.open(BytesIO(icon_png)).convert("RGBA")
        pic_im = Image.open(BytesIO(picture_png)).convert("RGBA")
    except Exception:
        logger.exception("Could not decode notification icon/image for composite")
        return None

    row_h = 96
    gap = 12

    def scale_to_height(im: Image.Image, target_h: int) -> Image.Image:
        w, h = im.size
        if h <= 0 or w <= 0:
            return im
        factor = target_h / float(h)
        new_w = max(1, int(round(w * factor)))
        new_h = target_h
        return im.resize((new_w, new_h), Image.Resampling.LANCZOS)

    icon_s = scale_to_height(icon_im, row_h)
    pic_s = scale_to_height(pic_im, row_h)

    max_thumb_w = 280
    pw, ph = pic_s.size
    if pw > max_thumb_w:
        factor = max_thumb_w / float(pw)
        pic_s = pic_s.resize(
            (max_thumb_w, max(1, int(round(ph * factor)))),
            Image.Resampling.LANCZOS,
        )

    iw, ih = icon_s.size
    pw, ph = pic_s.size
    total_w = iw + gap + pw
    total_h = max(ih, ph)

    canvas = Image.new("RGBA", (total_w, total_h), (0, 0, 0, 0))
    canvas.paste(icon_s, (0, (total_h - ih) // 2), icon_s)
    canvas.paste(pic_s, (iw + gap, (total_h - ph) // 2), pic_s)

    buf = BytesIO()
    canvas.save(buf, format="PNG", optimize=True)
    return buf.getvalue()


async def handle_notification(conn: JibeConnection, msg: JibeMessage) -> None:
    """Forward ``notification`` payloads to ``notify-send``."""
    payload = msg.payload
    app = payload.get("app")
    title = payload.get("title", "")
    body = payload.get("body", "")
    ts = payload.get("timestamp")

    if not isinstance(app, str) or not app.strip():
        await conn.send(format_error("malformed_payload", "notification requires app"))
        return
    if not isinstance(title, str):
        await conn.send(format_error("malformed_payload", "notification requires title string"))
        return
    if not isinstance(body, str):
        await conn.send(format_error("malformed_payload", "notification requires body string"))
        return
    if not isinstance(ts, int):
        await conn.send(format_error("malformed_payload", "notification requires integer timestamp"))
        return

    raw_app_name = payload.get("app_name")
    display_name = (
        raw_app_name if isinstance(raw_app_name, str) and raw_app_name.strip() else app
    )

    icon_data = _decode_b64_field(payload.get("icon"), "icon")
    image_data = _decode_b64_field(payload.get("image"), "image")

    icon_path: str | None = None
    composite_path: str | None = None

    try:
        attachment_png: bytes | None = None

        if icon_data and image_data:
            merged = compose_icon_and_thumbnail_png(icon_data, image_data)
            if merged is not None:
                attachment_png = merged
                icon_path = await asyncio.to_thread(_write_temp_png, icon_data, "icon")
            else:
                attachment_png = image_data
                icon_path = await asyncio.to_thread(_write_temp_png, icon_data, "icon")
        elif image_data:
            attachment_png = image_data
            if icon_data:
                icon_path = await asyncio.to_thread(_write_temp_png, icon_data, "icon")
        elif icon_data:
            icon_path = await asyncio.to_thread(_write_temp_png, icon_data, "icon")

        if attachment_png is not None:
            composite_path = await asyncio.to_thread(_write_temp_png, attachment_png, "img")

        args = ["notify-send", "--app-name", display_name]
        if icon_path:
            args += ["--icon", icon_path]
        if composite_path:
            args += ["-h", f"string:image-path:{composite_path}"]
        args += [title, body]

        try:
            proc = await asyncio.create_subprocess_exec(
                *args,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await proc.wait()
            if proc.returncode != 0:
                logger.warning(
                    "notify-send exited %s for notification from %s",
                    proc.returncode,
                    conn.id,
                )
        except FileNotFoundError:
            logger.exception("notify-send not found — cannot display mirrored notification")
            await conn.send(
                format_error("internal_error", "notify-send is not available on this system")
            )
        except Exception:
            logger.exception("notify-send failed for connection %s", conn.id)
            await conn.send(format_error("internal_error", "Failed to display desktop notification"))
    finally:
        _unlink_silently(icon_path)
        _unlink_silently(composite_path)
