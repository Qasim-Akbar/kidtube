import uuid
from datetime import date
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.api._common import get_child_or_404, get_plan_or_404
from app.core.clock import NowFn, family_today, get_now
from app.core.db import get_db
from app.core.deps import FamilyAccess, get_family_access
from app.engine import run_feed, run_projection
from app.schemas.feed import FeedOut, ProjectionOut
from app.services.engine_input import EngineInput, load_engine_input

router = APIRouter(tags=["feed"])


def _video(loaded: EngineInput, video_id: str) -> Any:
    return loaded.videos[video_id]


@router.get("/children/{child_id}/feed", response_model=FeedOut)
def get_feed(
    child_id: uuid.UUID,
    on_date: date | None = Query(default=None, alias="date", description="Parent-only preview of another local date"),
    access: FamilyAccess = Depends(get_family_access),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
) -> dict[str, Any]:
    child = get_child_or_404(db, access.family_id, child_id)
    if on_date is not None and access.parent is None:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only a parent can preview another date")
    plan = get_plan_or_404(db, child)
    today = on_date or family_today(now(), child.family.timezone)
    loaded = load_engine_input(db, child, plan, today)
    result = run_feed(loaded.feed_input())
    enriched = []
    for item in result["items"]:
        video = _video(loaded, item["video_id"])
        enriched.append({
            **item, "title": video.title, "thumbnail_url": video.thumbnail_url, "platform": video.platform,
            "platform_video_id": video.platform_video_id, "duration_seconds": video.duration_seconds,
        })
    return {
        **result, "items": enriched, "date": today, "pace_type": plan.pace_type,
        "unavailable_item_ids": loaded.unavailable_item_ids,
    }


@router.get("/children/{child_id}/projection", response_model=ProjectionOut)
def get_projection(
    child_id: uuid.UUID,
    days: int = Query(default=30, ge=1, le=91),
    access: FamilyAccess = Depends(get_family_access),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
) -> dict[str, Any]:
    child = get_child_or_404(db, access.family_id, child_id)
    plan = get_plan_or_404(db, child)
    today = family_today(now(), child.family.timezone)
    loaded = load_engine_input(db, child, plan, today)
    result = run_projection(loaded.projection_input(days))
    out_days = []
    for day in result["days"]:
        views = []
        for v in day["views"]:
            video = _video(loaded, v["video_id"])
            views.append({**v, "title": video.title, "thumbnail_url": video.thumbnail_url})
        out_days.append({**day, "views": views})
    return {
        **result, "days": out_days, "today": today, "pace_type": plan.pace_type,
        "unavailable_item_ids": loaded.unavailable_item_ids,
    }
