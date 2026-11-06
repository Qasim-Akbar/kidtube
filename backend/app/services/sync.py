"""Snapshot build (GET /sync/pull) and combined push (POST /sync/push).

Push reuses save_video (POST /videos) and ingest_events (POST /events); it only adds per-item result mapping.
"""

from __future__ import annotations

import hashlib
import json
import uuid
from datetime import date, datetime
from typing import Any

import httpx
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Child, Family, Plan, Video
from app.models.enums import Platform, VideoStatus
from app.schemas.child import ChildOut
from app.core.clock import family_today
from app.schemas.sync import (
    ChildHistory, HistorySeconds, HistoryViews, PushVideoResult, SnapshotChild, SnapshotOut,
)
from app.schemas.video import VideoOut
from app.services.engine_input import ChildHistory as LoadedHistory
from app.services.engine_input import load_histories
from app.services.events import ingest_events
from app.services.plan_views import load_plan_outs
from app.services.videos import VideoUrlError, save_video


def build_snapshot(db: Session, family_id: uuid.UUID, server_time: datetime) -> SnapshotOut:
    """The whole family in one response. Query count is constant: family, children, videos, plans, plan items, plus
    three for the per-child history (load_histories, the same loader the feed uses)."""
    family = db.get(Family, family_id)
    assert family is not None
    children = list(db.scalars(select(Child).where(Child.family_id == family_id).order_by(Child.created_at, Child.id)))
    videos = list(db.scalars(select(Video).where(Video.family_id == family_id).order_by(Video.added_at, Video.id)))
    plans = list(db.scalars(select(Plan).where(Plan.child_id.in_([c.id for c in children])).order_by(Plan.id))) if children else []
    plan_outs = load_plan_outs(db, plans)
    plan_by_child = {p.child_id: plan_outs[p.id] for p in plans}

    histories = load_histories(db, [c.id for c in children], family_today(server_time, family.timezone))
    snap_children = [
        SnapshotChild(
            **ChildOut.model_validate(c).model_dump(), plan=plan_by_child.get(c.id), history=_history_out(histories[c.id])
        )
        for c in children
    ]
    snap_videos = [VideoOut.model_validate(v) for v in videos]
    content = {
        "timezone": family.timezone,
        "children": [c.model_dump(mode="json") for c in snap_children],
        "videos": [v.model_dump(mode="json") for v in snap_videos],
    }
    digest = hashlib.sha256(json.dumps(content, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return SnapshotOut(
        server_time=server_time, timezone=family.timezone, snapshot_version=digest[:32],
        children=snap_children, videos=snap_videos,
    )


def _history_out(h: LoadedHistory) -> ChildHistory:
    return ChildHistory(
        views=[HistoryViews(plan_item_id=i, local_date=d, count=n) for i, d, n in h.views],
        watch_seconds=[HistorySeconds(local_date=d, seconds=n) for d, n in h.watch_seconds],
        app_seconds=[HistorySeconds(local_date=d, seconds=n) for d, n in h.app_seconds],
        time_extension_seconds=[HistorySeconds(local_date=d, seconds=n) for d, n in h.time_extension_seconds],
    )


def etag_matches(if_none_match: str | None, version: str) -> bool:
    if not if_none_match:
        return False
    for token in if_none_match.split(","):
        token = token.strip()
        if token == "*":
            return True
        if token.startswith("W/"):
            token = token[2:]
        if token.strip('"') == version:
            return True
    return False


def push_videos(
    db: Session, client: httpx.Client, raw_videos: list[Any], *, family_id: uuid.UUID, parent_id: uuid.UUID | None
) -> list[PushVideoResult]:
    results: list[PushVideoResult] = []
    for index, raw in enumerate(raw_videos):
        if parent_id is None:  # kid devices may only write events
            results.append(PushVideoResult(index=index, status="rejected", reason="forbidden", retryable=False))
            continue
        url = raw.get("url") if isinstance(raw, dict) else None
        if not isinstance(url, str):
            results.append(PushVideoResult(index=index, status="rejected", reason="url is required", retryable=False))
            continue
        try:
            saved = save_video(db, client, family_id=family_id, added_by=parent_id, url=url)
        except VideoUrlError as exc:
            results.append(PushVideoResult(index=index, status="rejected", reason=str(exc), retryable=False))
            continue
        video = saved.video
        # Saved but YouTube could not be reached: the video exists (not an error), verification is still owed.
        unverified = saved.created and video.platform is Platform.youtube and video.status is VideoStatus.pending
        results.append(PushVideoResult(
            index=index, status="created" if saved.created else "existing", video_id=video.id,
            video_status=video.status, retryable=unverified,
        ))
    return results


def push_events(
    db: Session, raw_events: list[Any], *, family_id: uuid.UUID, today: date, is_parent: bool,
    device_id: uuid.UUID | None, max_age_days: int,
) -> list[dict[str, Any]]:
    results = ingest_events(
        db, raw_events, family_id=family_id, today=today, is_parent=is_parent, device_id=device_id,
        max_age_days=max_age_days,
    )
    return [{**r, "retryable": False} for r in results]
