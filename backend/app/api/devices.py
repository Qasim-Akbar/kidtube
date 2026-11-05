import uuid
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from app.core.clock import NowFn, get_now
from app.core.config import Settings
from app.core.db import get_db
from app.core.deps import client_ip, get_app_settings, get_current_parent, get_pair_limiter
from app.core.ratelimit import FailureLimiter
from app.core.security import hash_device_token, hash_pair_code, new_device_token, new_pair_code
from app.models import Device, PairingCode, Parent
from app.models.enums import DeviceRole
from app.schemas.device import DeviceOut, PairCodeResponse, PairRequest, PairResponse

router = APIRouter(prefix="/devices", tags=["devices"])


@router.post("/pair-code", response_model=PairCodeResponse, status_code=status.HTTP_201_CREATED)
def create_pair_code(
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
    now: NowFn = Depends(get_now),
) -> PairCodeResponse:
    t = now()
    # Codes are looked up globally at pair time, so an active code must be unique across families.
    for _ in range(20):
        code = new_pair_code()
        code_hash = hash_pair_code(code, settings.jwt_secret)
        clash = db.scalar(
            select(PairingCode.id).where(
                PairingCode.code_hash == code_hash, PairingCode.used_at.is_(None), PairingCode.expires_at > t
            )
        )
        if clash is None:
            break
    else:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Could not generate a pairing code")
    expires_at = t + timedelta(minutes=settings.pair_code_minutes)
    db.add(PairingCode(family_id=parent.family_id, created_by=parent.id, code_hash=code_hash, expires_at=expires_at))
    db.commit()
    return PairCodeResponse(code=code, expires_at=expires_at)


@router.post("/pair", response_model=PairResponse, status_code=status.HTTP_201_CREATED)
def pair_device(
    body: PairRequest,
    request: Request,
    db: Session = Depends(get_db),
    settings: Settings = Depends(get_app_settings),
    limiter: FailureLimiter = Depends(get_pair_limiter),
    now: NowFn = Depends(get_now),
) -> PairResponse:
    t = now()
    ip = client_ip(request, settings)
    retry_after = limiter.retry_after(ip, t)
    if retry_after is not None:
        raise HTTPException(
            status.HTTP_429_TOO_MANY_REQUESTS, "Too many failed attempts", headers={"Retry-After": str(retry_after)}
        )

    invalid = HTTPException(status.HTTP_400_BAD_REQUEST, "Invalid or expired code")
    pairing = db.scalar(
        select(PairingCode).where(
            PairingCode.code_hash == hash_pair_code(body.code, settings.jwt_secret),
            PairingCode.used_at.is_(None),
            PairingCode.expires_at > t,
        )
    )
    if pairing is None:
        limiter.record_failure(ip, t)
        raise invalid
    # Atomic claim: only one concurrent request can flip used_at from NULL.
    claimed = db.execute(
        update(PairingCode).where(PairingCode.id == pairing.id, PairingCode.used_at.is_(None)).values(used_at=t)
    )
    if claimed.rowcount != 1:
        db.rollback()
        limiter.record_failure(ip, t)
        raise invalid

    token = new_device_token()
    device = Device(
        family_id=pairing.family_id, name=body.device_name, role=DeviceRole.kid,
        token_hash=hash_device_token(token), paired_at=t,
    )
    db.add(device)
    db.commit()
    limiter.reset(ip)
    return PairResponse(device_id=device.id, family_id=device.family_id, device_token=token)


@router.get("", response_model=list[DeviceOut])
def list_devices(parent: Parent = Depends(get_current_parent), db: Session = Depends(get_db)) -> list[Device]:
    return list(
        db.scalars(select(Device).where(Device.family_id == parent.family_id).order_by(Device.created_at, Device.id))
    )


@router.post("/{device_id}/revoke", response_model=DeviceOut)
def revoke_device(
    device_id: uuid.UUID,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
) -> Device:
    device = db.scalar(select(Device).where(Device.id == device_id, Device.family_id == parent.family_id))
    if device is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found")
    if device.revoked_at is None:
        device.revoked_at = now()
        db.commit()
    return device
