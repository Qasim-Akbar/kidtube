import hashlib
import hmac
import secrets
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Literal

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError

TokenType = Literal["access", "refresh"]
ALGORITHM = "HS256"

_hasher = PasswordHasher()
# Verified against when the email is unknown, so login timing doesn't reveal which emails exist.
DUMMY_HASH = _hasher.hash("not-a-real-password")


class TokenError(Exception):
    pass


def hash_password(password: str) -> str:
    return _hasher.hash(password)


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return _hasher.verify(password_hash, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


@dataclass(frozen=True)
class TokenClaims:
    sub: uuid.UUID
    family_id: uuid.UUID
    type: TokenType


def create_token(
    *, parent_id: uuid.UUID, family_id: uuid.UUID, token_type: TokenType,
    expires_in: timedelta, now: datetime, secret: str,
) -> str:
    payload = {
        "sub": str(parent_id),
        "family_id": str(family_id),
        "type": token_type,
        "exp": int((now + expires_in).timestamp()),
    }
    return jwt.encode(payload, secret, algorithm=ALGORITHM)


def decode_token(token: str, expected_type: TokenType, secret: str, now: datetime) -> TokenClaims:
    """Validate signature, type and expiry. Expiry is checked against the injected `now`."""
    try:
        payload = jwt.decode(
            token, secret, algorithms=[ALGORITHM],
            options={"verify_exp": False, "require": ["exp", "sub", "family_id", "type"]},
        )
        if payload["type"] != expected_type:
            raise TokenError("wrong token type")
        if int(payload["exp"]) <= int(now.timestamp()):
            raise TokenError("token expired")
        return TokenClaims(
            sub=uuid.UUID(payload["sub"]), family_id=uuid.UUID(payload["family_id"]), type=expected_type
        )
    except TokenError:
        raise
    except (jwt.PyJWTError, ValueError, TypeError, KeyError) as exc:
        raise TokenError("invalid token") from exc


def new_device_token() -> str:
    return secrets.token_urlsafe(32)


def hash_device_token(token: str) -> str:
    # Tokens are 256-bit random values, so a plain SHA-256 is sufficient (and survives JWT_SECRET rotation).
    return hashlib.sha256(token.encode()).hexdigest()


def new_pair_code() -> str:
    return f"{secrets.randbelow(1_000_000):06d}"


def hash_pair_code(code: str, secret: str) -> str:
    # 6-digit codes are brute-forceable offline, so key the hash with a server secret.
    return hmac.new(secret.encode(), code.encode(), hashlib.sha256).hexdigest()
