"""JWT issuance and verification."""

import pytest

from jibe.core.auth_jwt import JWTAuth, JWTAuthError


@pytest.mark.asyncio
async def test_create_and_verify_roundtrip(db):
    auth = JWTAuth(db)
    token, exp = await auth.create_access_token("u1", "alice", "admin")
    assert isinstance(token, str)
    assert exp > 0
    payload = await auth.verify_token(token)
    assert payload["id"] == "u1"
    assert payload["username"] == "alice"
    assert payload["role"] == "admin"


@pytest.mark.asyncio
async def test_verify_bad_token_raises(db):
    auth = JWTAuth(db)
    await auth.create_access_token("u1", "alice", "viewer")
    with pytest.raises(JWTAuthError):
        await auth.verify_token("not-a-jwt")


@pytest.mark.asyncio
async def test_secret_persisted_in_meta(db):
    auth = JWTAuth(db)
    await auth.create_access_token("a", "b", "admin")
    secret = await db.get_meta_value("jwt_secret")
    assert secret is not None
    assert len(secret) >= 32
