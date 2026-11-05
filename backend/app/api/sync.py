from typing import Any

import httpx
from fastapi import APIRouter, Depends, Header, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.clock import NowFn, family_today, get_now
from app.core.config import Settings
from app.core.db import get_db
from app.core.deps import FamilyAccess, get_app_settings, get_family_access, get_http_client
from app.models import Family
from app.schemas.sync import PushBody, PushResult, SnapshotOut
from app.services.sync import build_snapshot, etag_matches, push_events, push_videos

router = APIRouter(prefix="/sync", tags=["sync"])

CACHE_CONTROL = "private, no-cache"  # clients may keep it but must revalidate with If-None-Match


@router.get("/pull", response_model=SnapshotOut, responses={304: {"description": "Snapshot unchanged (If-None-Match)"}})
def pull(
    response: Response,
    if_none_match: str | None = Header(default=None),
    access: FamilyAccess = Depends(get_family_access),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
) -> Any:
    """The whole family snapshot (children, videos, plans with ordered items). The client replaces its cache."""
    snapshot = build_snapshot(db, access.family_id, now())
    headers = {"ETag": f'"{snapshot.snapshot_version}"', "Cache-Control": CACHE_CONTROL}
    if etag_matches(if_none_match, snapshot.snapshot_version):
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)
    response.headers.update(headers)
    return snapshot


@router.post("/push", response_model=PushResult)
def push(
    body: PushBody,
    access: FamilyAccess = Depends(get_family_access),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
    settings: Settings = Depends(get_app_settings),
    client: httpx.Client = Depends(get_http_client),
) -> dict[str, Any]:
    """Offline outbox flush: videos (parent token only) and events, with a result per item. Safe to resend."""
    family = db.scalar(select(Family).where(Family.id == access.family_id))
    assert family is not None
    videos = push_videos(
        db, client, body.videos, family_id=family.id, parent_id=access.parent.id if access.parent else None
    )
    events = push_events(
        db, body.events, family_id=family.id, today=family_today(now(), family.timezone),
        is_parent=access.parent is not None, device_id=access.device.id if access.device else None,
        max_age_days=settings.event_max_age_days,
    )
    return {"videos": videos, "events": events}
