"""Pure projection: simulate the queue forward assuming every offered view is completed."""

from __future__ import annotations

from datetime import date, timedelta
from typing import Sequence

from app.engine.feed import (
    DEFAULT_DURATION_SECONDS,
    build_feed,
    classify,
    count_views,
    evaluate_day,
    is_unlimited,
    non_active_day_status,
    target_views,
)
from app.engine.types import (
    ChildLimits,
    ItemState,
    PlanConfig,
    ProjectedDay,
    ProjectedView,
    Projection,
    SecondsRecord,
    ViewRecord,
)

HORIZON_DAYS = 90  # days simulated after today; offsets 0..90 inclusive
MAX_OUTPUT_DAYS = HORIZON_DAYS + 1


def project(
    plan: PlanConfig,
    items: Sequence[ItemState],
    views: Sequence[ViewRecord],
    seconds: Sequence[SecondsRecord],
    today: date,
    days: int,
    default_duration_seconds: int = DEFAULT_DURATION_SECONDS,
) -> Projection:
    excluded = sorted((i for i in items if is_unlimited(i)), key=lambda i: (i.position, i.id))
    sim_items = [i for i in items if not is_unlimited(i)]

    views_total, _ = count_views(sim_items, views, today)
    remaining_finite = sum(
        max(0, (target_views(i) or 0) - views_total.get(i.id, 0)) for i in classify(sim_items, views_total, today).pending
    )

    simulated: list[ProjectedDay] = []
    for offset in range(HORIZON_DAYS + 1):
        day = today + timedelta(days=offset)
        if offset == 0:  # exactly the feed logic, catch-up included
            feed = build_feed(plan, sim_items, views, seconds, [], ChildLimits(), today, default_duration_seconds)
            status = "scheduled" if feed.status not in ("outside_dates", "day_off") else feed.status
            picked = [(f.item_id, f.video_id) for f in feed.items]
        else:  # base pace only: the backlog is only ever absorbed on day 0
            status = non_active_day_status(plan, day) or "scheduled"
            picked = []
            if status == "scheduled":
                feed = evaluate_day(
                    plan, sim_items, views_total, {}, 0, False, day, 0, 0, default_duration_seconds
                )
                picked = [(f.item_id, f.video_id) for f in feed.items]
        for item_id, _ in picked:
            views_total[item_id] = views_total.get(item_id, 0) + 1
        simulated.append(ProjectedDay(day, status, tuple(ProjectedView(i, v) for i, v in picked)))

    end = today + timedelta(days=HORIZON_DAYS)
    unscheduled = sorted(classify(sim_items, views_total, end).pending, key=lambda i: (i.position, i.id))
    last_view_day = max((d.date for d in simulated if d.views), default=None)
    finish = last_view_day if not unscheduled else None

    shown = simulated[: max(0, min(days, MAX_OUTPUT_DAYS))]
    return Projection(
        days=tuple(shown),
        finish_date=finish,
        remaining_finite_views=remaining_finite,
        unscheduled_item_ids=tuple(i.id for i in unscheduled),
        excluded_unlimited_item_ids=tuple(i.id for i in excluded),
    )
