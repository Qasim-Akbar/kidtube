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
    VIDEOS_PER_DAY,
    ChildLimits,
    FeedResult,
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
MAX_STEPS_PER_DAY = 500  # hard guard on the within-day loop (it ends by itself: quotas and view targets are finite)


def _simulate_day(
    plan: PlanConfig,
    items: Sequence[ItemState],
    day: date,
    views_total: dict[str, int],
    views_today: dict[str, int],
    seconds_today: int,
    extra: int,
    backlog: int,
    default_duration_seconds: int,
    first: FeedResult | None = None,
) -> list[tuple[str, str]]:
    """Recompute the feed after every completed step until it offers nothing; returns the day's views in order.

    Counters are updated in place. `first` is an already-computed first step (day 0 uses build_feed for it).
    """
    by_id = {i.id: i for i in items}
    picked: list[tuple[str, str]] = []
    feed = first
    for _ in range(MAX_STEPS_PER_DAY):
        if feed is None:
            feed = evaluate_day(
                plan, items, views_total, views_today, seconds_today, False, day, backlog, extra,
                default_duration_seconds,
            )
        if not feed.items:
            break
        for f in feed.items:  # the child finishes everything offered in this step
            picked.append((f.item_id, f.video_id))
            views_total[f.item_id] = views_total.get(f.item_id, 0) + 1
            views_today[f.item_id] = views_today.get(f.item_id, 0) + 1
            duration = by_id[f.item_id].duration_seconds
            seconds_today += duration if duration is not None else default_duration_seconds
        feed = None
    return picked


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

    views_total, views_today0 = count_views(sim_items, views, today)
    remaining_finite = sum(
        max(0, (target_views(i) or 0) - views_total.get(i.id, 0)) for i in classify(sim_items, views_total, today).pending
    )

    simulated: list[ProjectedDay] = []
    for offset in range(HORIZON_DAYS + 1):
        day = today + timedelta(days=offset)
        if offset == 0:  # first step is exactly the feed logic, catch-up included
            feed = build_feed(plan, sim_items, views, seconds, [], ChildLimits(), today, default_duration_seconds)
            status = "scheduled" if feed.status not in ("outside_dates", "day_off") else feed.status
            base = plan.pace_value if plan.pace_type == VIDEOS_PER_DAY else plan.pace_value * 60
            picked = _simulate_day(
                plan, sim_items, today, views_total, views_today0,
                sum(r.seconds for r in seconds if r.local_date == today),
                max(0, feed.quota_today - base), feed.backlog, default_duration_seconds, first=feed,
            )
        else:  # base pace only: the backlog is only ever absorbed on day 0
            status = non_active_day_status(plan, day) or "scheduled"
            picked = []
            if status == "scheduled":
                picked = _simulate_day(plan, sim_items, day, views_total, {}, 0, 0, 0, default_duration_seconds)
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
