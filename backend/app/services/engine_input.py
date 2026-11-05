"""Loads one child's data from the DB into the JSON shape the pure engine consumes (app.engine.run_feed/run_projection).

Kept separate from the endpoints so it can be unit-tested. Views are aggregated in one SQL query (GROUP BY
plan_item_id, local_date, supported by ix_events_child_id_type_plan_item_id_local_date), never per item.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.engine.feed import CATCH_UP_WINDOW_DAYS
from app.engine.types import ChildLimits, ItemState, PlanConfig
from app.models import Child, Event, Plan, PlanItem, Video
from app.models.enums import EventType, PlanItemStatus, VideoStatus


@dataclass(frozen=True)
class EngineInput:
    today: date
    plan: dict[str, Any]
    items: list[dict[str, Any]]
    views: list[dict[str, Any]]
    seconds: list[dict[str, Any]]
    app_seconds: list[dict[str, Any]]
    limits: dict[str, Any]
    unavailable_item_ids: list[str]
    videos: dict[str, Video]  # by str(video.id), for response enrichment

    def feed_input(self) -> dict[str, Any]:
        return {
            "plan": self.plan, "items": self.items, "views": self.views, "seconds": self.seconds,
            "app_seconds": self.app_seconds, "limits": self.limits, "today": self.today.isoformat(),
        }

    def projection_input(self, days: int) -> dict[str, Any]:
        return {
            "plan": self.plan, "items": self.items, "views": self.views, "seconds": self.seconds,
            "today": self.today.isoformat(), "days": days,
        }


def plan_config(plan: Plan) -> PlanConfig:
    return PlanConfig(
        pace_type=plan.pace_type.value, pace_value=plan.pace_value, active_weekdays=plan.active_weekdays,
        max_extra_per_day=plan.max_extra_per_day, start_date=plan.start_date, end_date=plan.end_date,
    )


def _sum_seconds_by_date(
    db: Session, child_id: Any, event_type: EventType, lo: date, hi: date
) -> list[dict[str, Any]]:
    total = func.sum(Event.payload["seconds"].as_float())
    rows = db.execute(
        select(Event.local_date, total)
        .where(Event.child_id == child_id, Event.type == event_type, Event.local_date >= lo, Event.local_date <= hi)
        .group_by(Event.local_date)
        .order_by(Event.local_date)
    ).all()
    return [{"local_date": d.isoformat(), "seconds": int(round(s or 0))} for d, s in rows]


def _app_session_seconds(db: Session, child_id: Any, today: date) -> list[dict[str, Any]]:
    payloads = db.scalars(
        select(Event.payload).where(
            Event.child_id == child_id, Event.type == EventType.app_session, Event.local_date == today
        )
    ).all()
    total = 0
    for p in payloads:  # validated and normalised at ingest: ISO-8601 start/end with end >= start
        start, end = datetime.fromisoformat(p["start"]), datetime.fromisoformat(p["end"])
        total += max(0, int((end - start).total_seconds()))
    return [{"local_date": today.isoformat(), "seconds": total}] if payloads else []


def load_engine_input(db: Session, child: Child, plan: Plan, today: date) -> EngineInput:
    rows = db.execute(
        select(PlanItem, Video)
        .join(Video, Video.id == PlanItem.video_id)
        .where(PlanItem.plan_id == plan.id)
        .order_by(PlanItem.position, PlanItem.id)
    ).all()

    items: list[dict[str, Any]] = []
    unavailable: list[str] = []
    videos: dict[str, Video] = {}
    for item, video in rows:
        videos[str(video.id)] = video
        if video.status is VideoStatus.unavailable and item.status is PlanItemStatus.active:
            unavailable.append(str(item.id))  # never offered; the parent is told instead
            continue
        items.append(
            ItemState(
                id=str(item.id), video_id=str(video.id), position=item.position, required=item.required,
                view_target=item.view_target, daily_cap=item.daily_cap, anchor_date=item.anchor_date,
                on_miss=item.on_miss.value, status=item.status.value, duration_seconds=video.duration_seconds,
                added_on=item.added_on,
            ).to_dict()
        )

    item_ids = [i["id"] for i in items]
    view_rows = db.execute(
        select(Event.plan_item_id, Event.local_date, func.count())
        .where(
            Event.child_id == child.id,
            Event.type == EventType.view_complete,
            Event.plan_item_id.in_([uuid.UUID(i) for i in item_ids]),
            Event.local_date <= today,
        )
        .group_by(Event.plan_item_id, Event.local_date)
        .order_by(Event.plan_item_id, Event.local_date)
    ).all()
    views = [
        {"item_id": str(pid), "local_date": d.isoformat()} for pid, d, n in view_rows for _ in range(n)
    ]

    seconds = _sum_seconds_by_date(
        db, child.id, EventType.watch_progress, today - timedelta(days=CATCH_UP_WINDOW_DAYS), today
    )
    extension = sum(
        r["seconds"] for r in _sum_seconds_by_date(db, child.id, EventType.time_extension, today, today)
    )
    limits = ChildLimits(child.daily_app_minutes, extension)

    return EngineInput(
        today=today, plan=plan_config(plan).to_dict(), items=items, views=views, seconds=seconds,
        app_seconds=_app_session_seconds(db, child.id, today), limits=limits.to_dict(),
        unavailable_item_ids=unavailable, videos=videos,
    )
