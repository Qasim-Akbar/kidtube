from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.clock import NowFn, get_now
from app.core.config import Settings
from app.core.db import get_db
from app.core.deps import LoginLimiters, client_ip, get_app_settings, get_login_limiters
from app.core.security import (
    DUMMY_HASH, TokenError, create_token, decode_token, hash_password, verify_password,
)
from app.models import Family, Parent
from app.schemas.auth import (
    AccessTokenResponse, LoginRequest, RefreshRequest, RegisterRequest, RegisterResponse, TokenResponse,
)

router = APIRouter(prefix="/auth", tags=["auth"])


def _issue(parent: Parent, token_type: str, settings: Settings, now: NowFn) -> str:
    expires = (
        timedelta(minutes=settings.access_token_minutes)
        if token_type == "access"
        else timedelta(days=settings.refresh_token_days)
    )
    return create_token(
        parent_id=parent.id, family_id=parent.family_id, token_type=token_type,  # type: ignore[arg-type]
        expires_in=expires, now=now(), secret=settings.jwt_secret,
    )


@router.post("/register", response_model=RegisterResponse, status_code=status.HTTP_201_CREATED)
def register(
    body: RegisterRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
) -> RegisterResponse:
    if not settings.allow_registration:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Registration is disabled")
    if db.scalar(select(Parent.id).where(Parent.email == body.email)) is not None:
        raise HTTPException(status.HTTP_409_CONFLICT, "Email already registered")
    family = Family(name=body.family_name, timezone=body.timezone or settings.default_timezone)
    parent = Parent(family=family, email=body.email, password_hash=hash_password(body.password))
    db.add_all([family, parent])
    try:
        db.commit()
    except IntegrityError:  # lost a race with a concurrent registration of the same email
        db.rollback()
        raise HTTPException(status.HTTP_409_CONFLICT, "Email already registered") from None
    return RegisterResponse(parent_id=parent.id, family_id=family.id, email=parent.email)


@router.post("/login", response_model=TokenResponse)
def login(
    body: LoginRequest,
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
    limiters: LoginLimiters = Depends(get_login_limiters),
    now: NowFn = Depends(get_now),
) -> TokenResponse:
    t = now()
    ip = client_ip(request, settings)
    # The email key is limited whether or not the account exists, so lockouts can't reveal which emails exist.
    waits = [w for w in (limiters.by_ip.retry_after(ip, t), limiters.by_email.retry_after(body.email, t)) if w]
    if waits:  # checked before the password, so even a correct password is refused while locked out
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS, "Too many failed login attempts",
            headers={"Retry-After": str(max(waits))},
        )

    parent = db.scalar(select(Parent).where(Parent.email == body.email))
    ok = verify_password(body.password, parent.password_hash if parent else DUMMY_HASH)
    if parent is None or not ok:
        limiters.by_ip.record_failure(ip, t)
        limiters.by_email.record_failure(body.email, t)
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "Incorrect email or password", headers={"WWW-Authenticate": "Bearer"}
        )
    # Only the email counter resets: a successful login on one's own account must not clear the IP's count.
    limiters.by_email.reset(body.email)
    return TokenResponse(
        access_token=_issue(parent, "access", settings, now),
        refresh_token=_issue(parent, "refresh", settings, now),
    )


@router.post("/refresh", response_model=AccessTokenResponse)
def refresh(
    body: RefreshRequest,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
    now: NowFn = Depends(get_now),
) -> AccessTokenResponse:
    try:
        claims = decode_token(body.refresh_token, "refresh", settings.jwt_secret, now())
    except TokenError:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "Invalid refresh token", headers={"WWW-Authenticate": "Bearer"}
        ) from None
    parent = db.get(Parent, claims.sub)
    if parent is None or parent.family_id != claims.family_id:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "Invalid refresh token", headers={"WWW-Authenticate": "Bearer"}
        )
    return AccessTokenResponse(access_token=_issue(parent, "access", settings, now))
