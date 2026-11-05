from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.clock import NowFn, get_now
from app.core.config import Settings
from app.core.db import get_db
from app.core.deps import get_app_settings
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
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
    now: NowFn = Depends(get_now),
) -> TokenResponse:
    parent = db.scalar(select(Parent).where(Parent.email == body.email))
    ok = verify_password(body.password, parent.password_hash if parent else DUMMY_HASH)
    if parent is None or not ok:
        raise HTTPException(
            status.HTTP_401_UNAUTHORIZED, "Incorrect email or password", headers={"WWW-Authenticate": "Bearer"}
        )
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
