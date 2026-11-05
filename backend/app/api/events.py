from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.clock import NowFn, family_today, get_now
from app.core.config import Settings
from app.core.db import get_db
from app.core.deps import FamilyAccess, get_app_settings, get_family_access
from app.models import Family
from app.schemas.event import EventBatch, EventBatchResult
from app.services.events import ingest_events

router = APIRouter(prefix="/events", tags=["events"])


@router.post("", response_model=EventBatchResult)
def post_events(
    body: EventBatch,
    access: FamilyAccess = Depends(get_family_access),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
    settings: Settings = Depends(get_app_settings),
) -> EventBatchResult:
    """Idempotent batch ingest (parent or kid device). Each event gets accepted / duplicate / rejected(reason)."""
    family = db.scalar(select(Family).where(Family.id == access.family_id))
    assert family is not None
    results = ingest_events(
        db, body.events, family_id=family.id, today=family_today(now(), family.timezone),
        is_parent=access.parent is not None, device_id=access.device.id if access.device else None,
        max_age_days=settings.event_max_age_days,
    )
    return EventBatchResult(results=results)  # type: ignore[arg-type]
