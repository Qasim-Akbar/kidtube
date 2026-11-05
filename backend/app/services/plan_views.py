"""Plan -> response-schema builders, shared by the plan endpoints and the sync snapshot."""

from __future__ import annotations

import uuid
from collections.abc import Sequence

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Plan, PlanItem, Video
from app.schemas.plan import PlanItemOut, PlanOut, VideoSummary


def item_out(item: PlanItem, video: Video) -> PlanItemOut:
    return PlanItemOut(
        id=item.id, video_id=item.video_id, position=item.position, required=item.required,
        view_target=item.view_target, daily_cap=item.daily_cap, watch_threshold=item.watch_threshold,
        anchor_date=item.anchor_date, on_miss=item.on_miss, status=item.status, added_on=item.added_on,
        video=VideoSummary(
            id=video.id, title=video.title, thumbnail_url=video.thumbnail_url, platform=video.platform,
            platform_video_id=video.platform_video_id, status=video.status, duration_seconds=video.duration_seconds,
        ),
    )


def plan_out(plan: Plan, rows: Sequence[tuple[PlanItem, Video]]) -> PlanOut:
    """`rows` are this plan's (item, video) pairs, already ordered by (position, id)."""
    return PlanOut(
        id=plan.id, child_id=plan.child_id, pace_type=plan.pace_type, pace_value=plan.pace_value,
        active_weekdays=plan.active_weekdays, max_extra_per_day=plan.max_extra_per_day, start_date=plan.start_date,
        end_date=plan.end_date, version=plan.version, items=[item_out(i, v) for i, v in rows],
    )


def load_plan_out(db: Session, plan: Plan) -> PlanOut:
    return load_plan_outs(db, [plan])[plan.id]


def load_plan_outs(db: Session, plans: Sequence[Plan]) -> dict[uuid.UUID, PlanOut]:
    """Several plans with a single items query (constant query count regardless of how many plans)."""
    if not plans:
        return {}
    rows = db.execute(
        select(PlanItem, Video).join(Video, Video.id == PlanItem.video_id)
        .where(PlanItem.plan_id.in_([p.id for p in plans])).order_by(PlanItem.position, PlanItem.id)
    ).all()
    by_plan: dict[uuid.UUID, list[tuple[PlanItem, Video]]] = {p.id: [] for p in plans}
    for item, video in rows:
        by_plan[item.plan_id].append((item, video))
    return {p.id: plan_out(p, by_plan[p.id]) for p in plans}


def ordered_items(db: Session, plan: Plan) -> list[PlanItem]:
    return list(db.scalars(select(PlanItem).where(PlanItem.plan_id == plan.id).order_by(PlanItem.position, PlanItem.id)))


def renumber(items: list[PlanItem]) -> bool:
    """Make positions dense (0..n-1) in list order. True when anything moved."""
    changed = False
    for index, item in enumerate(items):
        if item.position != index:
            item.position = index
            changed = True
    return changed
