"""Per-child reports. Every number comes from events.local_date (the child's day), never from UTC timestamps.

Query count is constant: no query per day or per video. Group-by queries return one row per (day, video/item), so
the result size depends on the days and videos in play, not on how many events there are.
"""

from __future__ import annotations

import calendar
import uuid
from collections import defaultdict
from datetime import date, datetime, timedelta
from typing import Any, Literal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.engine import run_projection
from app.engine.feed import is_complete
from app.engine.types import ItemState
from app.models import Child, Event, Plan, PlanItem, Video
from app.models.enums import EventType, PaceType, PlanItemStatus
from app.services.engine_input import load_engine_input

Range = Literal["day", "week", "month"]
TOP_VIDEOS = 10


def range_bounds(kind: Range, anchor: date) -> tuple[date, date]:
    if kind == "day":
        return anchor, anchor
    if kind == "week":  # Monday..Sunday
        monday = anchor - timedelta(days=anchor.weekday())
        return monday, monday + timedelta(days=6)
    return anchor.replace(day=1), anchor.replace(day=calendar.monthrange(anchor.year, anchor.month)[1])


def _daterange(lo: date, hi: date) -> list[date]:
    return [lo + timedelta(days=n) for n in range((hi - lo).days + 1)]


def _session_seconds(payload: dict[str, Any]) -> int:  # validated and normalised at ingest
    start, end = datetime.fromisoformat(payload["start"]), datetime.fromisoformat(payload["end"])
    return max(0, int((end - start).total_seconds()))


def build_report(
    db: Session, child: Child, plan: Plan | None, kind: Range, anchor: date, today: date
) -> dict[str, Any]:
    lo, hi = range_bounds(kind, anchor)
    days = _daterange(lo, hi)
    in_range = (Event.child_id == child.id, Event.local_date >= lo, Event.local_date <= hi)

    # 1. views per (day, plan item)
    view_rows = db.execute(
        select(Event.local_date, Event.plan_item_id, func.count())
        .where(*in_range, Event.type == EventType.view_complete)
        .group_by(Event.local_date, Event.plan_item_id)
        .order_by(Event.local_date, Event.plan_item_id)
    ).all()
    # 2. watch seconds per (day, video); grouping (not filtering) on the JSON key keeps it portable
    video_key = Event.payload["video_id"].as_string()
    watch_rows = db.execute(
        select(Event.local_date, video_key.label("video_id"), func.sum(Event.payload["seconds"].as_float()))
        .where(*in_range, Event.type == EventType.watch_progress)
        .group_by(Event.local_date, video_key)
        .order_by(Event.local_date, video_key)
    ).all()
    # 3. app sessions: a duration needs end - start of two ISO strings, which has no portable SQL form
    session_rows = db.execute(
        select(Event.local_date, Event.payload).where(*in_range, Event.type == EventType.app_session)
        .order_by(Event.id)
    ).all()
    # 4. playback errors per (video, code)
    code_key = Event.payload["code"].as_string()
    error_rows = db.execute(
        select(video_key.label("video_id"), code_key.label("code"), func.count())
        .where(*in_range, Event.type == EventType.playback_error)
        .group_by(video_key, code_key)
        .order_by(video_key, code_key)
    ).all()

    # 5. the plan's items (item -> video) and 6. all-time views per item (completion is not range-bound)
    item_rows = (
        db.execute(
            select(PlanItem, Video).join(Video, Video.id == PlanItem.video_id)
            .where(PlanItem.plan_id == plan.id).order_by(PlanItem.position, PlanItem.id)
        ).all()
        if plan is not None else []
    )
    total_rows = db.execute(
        select(Event.plan_item_id, func.count())
        .where(Event.child_id == child.id, Event.type == EventType.view_complete, Event.local_date <= today)
        .group_by(Event.plan_item_id)
        .order_by(Event.plan_item_id)
    ).all()
    views_total = {str(pid): n for pid, n in total_rows if pid is not None}
    video_of_item = {item.id: item.video_id for item, _ in item_rows}

    # ---- per-day rows
    watch_by_day: dict[date, float] = defaultdict(float)
    app_by_day: dict[date, int] = defaultdict(int)
    views_by_day: dict[date, int] = defaultdict(int)
    views_by_video: dict[uuid.UUID, int] = defaultdict(int)
    views_by_item: dict[uuid.UUID, int] = defaultdict(int)
    watch_by_video: dict[str, float] = defaultdict(float)
    for d, item_id, n in view_rows:
        views_by_day[d] += n
        if item_id is not None:
            views_by_item[item_id] += n
            if item_id in video_of_item:  # views of since-deleted plan items still count per day, not per video
                views_by_video[video_of_item[item_id]] += n
    for d, vid, seconds in watch_rows:
        watch_by_day[d] += seconds or 0
        if vid is not None:
            watch_by_video[vid] += seconds or 0
    for d, payload in session_rows:
        app_by_day[d] += _session_seconds(payload)

    day_rows = []
    for d in days:
        watch, app = int(round(watch_by_day[d])), app_by_day[d]
        day_rows.append({
            "date": d, "watch_seconds": watch, "app_seconds": app, "browsing_seconds": max(0, app - watch),
            "views_completed": views_by_day[d],
        })
    total_watch = sum(r["watch_seconds"] for r in day_rows)
    total_app = sum(r["app_seconds"] for r in day_rows)
    totals = {
        "watch_seconds": total_watch, "app_seconds": total_app, "browsing_seconds": max(0, total_app - total_watch),
        "views_completed": sum(r["views_completed"] for r in day_rows),
    }

    # ---- top videos and error labels (7. one family-scoped video lookup)
    stats: dict[uuid.UUID, tuple[int, int]] = {}
    for vid, n in views_by_video.items():
        stats[vid] = (n, 0)
    for raw, seconds in watch_by_video.items():
        try:
            vid = uuid.UUID(raw)
        except ValueError:  # cannot happen for validated events
            continue
        stats[vid] = (stats.get(vid, (0, 0))[0], int(round(seconds)))
    top = sorted(stats.items(), key=lambda kv: (-kv[1][0], -kv[1][1], str(kv[0])))[:TOP_VIDEOS]
    error_video_ids = {uuid.UUID(v) for v, c, _ in error_rows if v is not None and c is not None}
    wanted = {v for v, _ in top} | error_video_ids
    videos = (
        {v.id: v for v in db.scalars(select(Video).where(Video.family_id == child.family_id, Video.id.in_(wanted)))}
        if wanted else {}
    )
    top_videos = [
        {
            "video_id": vid, "title": videos[vid].title if vid in videos else None,
            "thumbnail_url": videos[vid].thumbnail_url if vid in videos else None, "views": n, "watch_seconds": s,
        }
        for vid, (n, s) in top
    ]
    errors: dict[tuple[uuid.UUID, str], int] = defaultdict(int)
    for v, c, n in error_rows:  # a code stored as 150 and as "150" is the same code
        if v is not None and c is not None:
            errors[(uuid.UUID(v), str(c))] += n
    playback_errors = [
        {"video_id": v, "title": videos[v].title if v in videos else None, "code": c, "count": n}
        for (v, c), n in sorted(errors.items(), key=lambda kv: (-kv[1], str(kv[0][0]), kv[0][1]))
    ]

    # ---- per-item completion
    per_video = []
    for item, video in item_rows:
        if item.status is PlanItemStatus.dropped:
            continue
        state = ItemState(
            id=str(item.id), video_id=str(video.id), position=item.position, required=item.required,
            view_target=item.view_target, daily_cap=item.daily_cap, anchor_date=item.anchor_date,
            on_miss=item.on_miss.value, status=item.status.value, duration_seconds=video.duration_seconds,
            added_on=item.added_on,
        )
        per_video.append({
            "item_id": item.id, "video_id": video.id, "title": video.title, "required": item.required,
            "status": item.status, "view_target": item.view_target, "views_total": views_total.get(str(item.id), 0),
            "views_in_range": views_by_item.get(item.id, 0), "completed": is_complete(state, views_total),
        })

    planned = _planned_vs_watched(plan, day_rows, lo, hi, today) if plan is not None else None
    progress = None
    if plan is not None:
        loaded = load_engine_input(db, child, plan, today)
        proj = run_projection(loaded.projection_input(0))  # days only truncates the output; finish_date is horizon-wide
        progress = {
            "as_of": today, "finish_date": proj["finish_date"], "remaining_finite_views": proj["remaining_finite_views"],
        }

    return {
        "child_id": child.id, "range": kind, "date": anchor, "start": lo, "end": hi, "days": day_rows,
        "totals": totals, "top_videos": top_videos, "per_video": per_video, "playback_errors": playback_errors,
        "planned_vs_watched": planned, "plan_progress": progress,
    }


def _planned_vs_watched(
    plan: Plan, day_rows: list[dict[str, Any]], lo: date, hi: date, today: date
) -> dict[str, Any]:
    """Expected = pace per active day, counted only on days inside both the range, the plan's dates and <= today.
    `actual` is summed over the same days so the two sides are comparable."""
    start = max(lo, plan.start_date)
    end = min(hi, today, plan.end_date) if plan.end_date is not None else min(hi, today)
    minutes = plan.pace_type is PaceType.minutes_per_day
    counted = [r for r in day_rows if start <= r["date"] <= end]
    active = [r for r in counted if plan.active_weekdays & (1 << r["date"].weekday())]
    per_day = plan.pace_value * (60 if minutes else 1)
    return {
        "pace_type": plan.pace_type, "unit": "seconds" if minutes else "videos",
        "window_start": start if start <= end else None, "window_end": end if start <= end else None,
        "active_days": len(active), "expected": per_day * len(active),
        "actual": sum(r["watch_seconds" if minutes else "views_completed"] for r in counted),
    }
