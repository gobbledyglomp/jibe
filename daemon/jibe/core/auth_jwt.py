"""JWT access tokens for the localhost dashboard REST API."""

from __future__ import annotations

import logging
import secrets
import time
from typing import Any

import jwt

from jibe.core.config import JWT_EXPIRY_SECONDS, JWT_SECRET_META_KEY
from jibe.core.db import JibeDatabase

logger = logging.getLogger(__name__)


class JWTAuthError(Exception):
    """Raised when a token cannot be verified."""


class JWTAuth:
    """Issue and verify HS256 JWTs; signing secret lives in SQLite ``meta``."""

    def __init__(self, db: JibeDatabase) -> None:
        self._db = db

    async def _ensure_secret(self) -> str:
        """Load or create the JWT signing secret."""
        raw = await self._db.get_meta_value(JWT_SECRET_META_KEY)
        if raw:
            return raw
        secret = secrets.token_hex(32)
        await self._db.set_meta_value(JWT_SECRET_META_KEY, secret)
        logger.debug("Generated new JWT signing secret")
        return secret

    async def create_access_token(
        self,
        user_id: str,
        username: str,
        role: str,
    ) -> tuple[str, float]:
        """Return ``(token, expires_at_unix)``."""
        secret = await self._ensure_secret()
        now = time.time()
        exp = now + JWT_EXPIRY_SECONDS
        payload: dict[str, Any] = {
            "sub": user_id,
            "username": username,
            "role": role,
            "iat": int(now),
            "exp": int(exp),
        }
        token = jwt.encode(payload, secret, algorithm="HS256")
        out = token if isinstance(token, str) else token.decode()
        return out, exp

    async def verify_token(self, token: str) -> dict[str, str]:
        """Decode a Bearer token into ``id``, ``username``, ``role``."""
        secret = await self._ensure_secret()
        try:
            decoded = jwt.decode(token, secret, algorithms=["HS256"])
        except jwt.ExpiredSignatureError as e:
            raise JWTAuthError("expired") from e
        except jwt.InvalidTokenError as e:
            raise JWTAuthError("invalid") from e
        uid = decoded.get("sub")
        username = decoded.get("username")
        role = decoded.get("role")
        if not uid or not username or role not in ("admin", "viewer"):
            raise JWTAuthError("invalid payload") from None
        return {"id": str(uid), "username": str(username), "role": str(role)}
