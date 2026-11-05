import uuid
from dataclasses import dataclass

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.clock import NowFn, get_now
from app.core.config import Settings
from app.core.db import get_db
from app.core.ratelimit import FailureLimiter
from app.core.security import TokenError, decode_token, hash_device_token
from app.models import Device, Parent

_bearer = HTTPBearer(auto_error=False)


@dataclass(frozen=True)
class LoginLimiters:
    by_ip: FailureLimiter
    by_email: FailureLimiter


def get_app_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_pair_limiter(request: Request) -> FailureLimiter:
    return request.app.state.pair_limiter


def get_login_limiters(request: Request) -> "LoginLimiters":
    return LoginLimiters(request.app.state.login_ip_limiter, request.app.state.login_email_limiter)


def client_ip(request: Request, settings: Settings) -> str:
    if settings.trust_proxy_headers:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[-1].strip()  # entry appended by our own proxy
    return request.client.host if request.client else "unknown"


def _unauthorized() -> HTTPException:
    return HTTPException(
        status.HTTP_401_UNAUTHORIZED, "Invalid or missing credentials", headers={"WWW-Authenticate": "Bearer"}
    )


def _parent_from_token(token: str, db: Session, settings: Settings, now: NowFn) -> Parent:
    try:
        claims = decode_token(token, "access", settings.jwt_secret, now())
    except TokenError:
        raise _unauthorized() from None
    parent = db.get(Parent, claims.sub)
    if parent is None or parent.family_id != claims.family_id:
        raise _unauthorized()
    return parent


def _device_from_token(token: str, db: Session) -> Device:
    device = db.scalar(select(Device).where(Device.token_hash == hash_device_token(token)))
    if device is None or device.revoked_at is not None:
        raise _unauthorized()
    return device


def get_current_parent(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
    now: NowFn = Depends(get_now),
) -> Parent:
    """Parent-only: requires a valid JWT *access* token. Device tokens are always rejected."""
    if creds is None:
        raise _unauthorized()
    return _parent_from_token(creds.credentials, db, settings, now)


def get_current_device(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: Session = Depends(get_db),
) -> Device:
    if creds is None:
        raise _unauthorized()
    return _device_from_token(creds.credentials, db)


@dataclass(frozen=True)
class FamilyAccess:
    family_id: uuid.UUID
    parent: Parent | None = None
    device: Device | None = None


def get_family_access(
    creds: HTTPAuthorizationCredentials | None = Depends(_bearer),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
    now: NowFn = Depends(get_now),
) -> FamilyAccess:
    """For read endpoints open to both parents and kid devices; resolves the caller's family."""
    if creds is None:
        raise _unauthorized()
    token = creds.credentials
    if "." in token:  # JWTs contain dots; opaque device tokens (urlsafe base64) never do
        parent = _parent_from_token(token, db, settings, now)
        return FamilyAccess(family_id=parent.family_id, parent=parent)
    device = _device_from_token(token, db)
    return FamilyAccess(family_id=device.family_id, device=device)
