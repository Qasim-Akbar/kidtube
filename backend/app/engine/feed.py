"""Pure feed logic: what may a child watch today? (SPEC section 7.)

No I/O, no clock, no ORM. Deterministic: ordering is always (anchored-today first, position, id).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Mapping, Sequence

from app.engine.types import (
    VIDEOS_PER_DAY,
    AppSecondsRecord,
    ChildLimits,
    FeedItem,
    FeedResult,
    ItemState,
    PlanConfig,
    SecondsRecord,
    ViewRecord,
)

DEFAULT_DURATION_SECONDS = 300
CATCH_UP_WINDOW_DAYS = 14


# ---------------------------------------------------------------- calendar

def is_within_dates(plan: PlanConfig, d: date) -> bool:
    return plan.start_date <= d and (plan.end_date is None or d <= plan.end_date)


def is_active_day(plan: PlanConfig, d: date) -> bool:
    return is_within_dates(plan, d) and bool(plan.active_weekdays & (1 << d.weekday()))


def non_active_day_status(plan: PlanConfig, d: date) -> str | None:
    """"outside_dates" / "day_off" when the plan does not run on `d`, else None."""
    if not is_within_dates(plan, d):
        return "outside_dates"
    if not plan.active_weekdays & (1 << d.weekday()):
        return "day_off"
    return None


# ---------------------------------------------------------------- items

def target_views(item: ItemState) -> int | None:
    """Finite number of views that completes the item; a required item with no target needs 1."""
    if item.view_target is not None:
        return item.view_target
    return 1 if item.required else None


def is_unlimited(item: ItemState) -> bool:
    return item.status == "active" and target_views(item) is None


def is_complete(item: ItemState, views_total: Mapping[str, int]) -> bool:
    if item.status == "completed":
        return True
    n = target_views(item)
    return n is not None and views_total.get(item.id, 0) >= n


def sort_key(item: ItemState, today: date) -> tuple[int, int, str]:
    return (0 if item.anchor_date == today else 1, item.position, item.id)


@dataclass(frozen=True)
class Classified:
    pending: tuple[ItemState, ...]  # active, incomplete, not excluded by on_miss
    skipped: tuple[ItemState, ...]
    dropped: tuple[ItemState, ...]


def classify(items: Sequence[ItemState], views_total: Mapping[str, int], today: date) -> Classified:
    pending: list[ItemState] = []
    skipped: list[ItemState] = []
    dropped: list[ItemState] = []
    for item in items:
        if item.status != "active" or is_complete(item, views_total):
            continue
        if item.anchor_date is not None and item.anchor_date < today:
            if item.on_miss == "skip":
                skipped.append(item)
                continue
            if item.on_miss == "drop":
                dropped.append(item)
                continue
        pending.append(item)  # includes future-anchored and daily-capped items
    return Classified(tuple(pending), tuple(skipped), tuple(dropped))


# ---------------------------------------------------------------- counting

def count_views(
    items: Sequence[ItemState], views: Sequence[ViewRecord], today: date
) -> tuple[dict[str, int], dict[str, int]]:
    """(views_total, views_today) per item id. Unknown items and future-dated records are ignored."""
    known = {i.id for i in items}
    total: dict[str, int] = {}
    today_by: dict[str, int] = {}
    for v in views:
        if v.item_id not in known or v.local_date > today:
            continue
        total[v.item_id] = total.get(v.item_id, 0) + 1
        if v.local_date == today:
            today_by[v.item_id] = today_by.get(v.item_id, 0) + 1
    return total, today_by


def compute_backlog(
    plan: PlanConfig,
    items: Sequence[ItemState],
    views: Sequence[ViewRecord],
    seconds: Sequence[SecondsRecord],
    today: date,
) -> int:
    """Missed pace over the last 14 days before `today` (videos, or seconds in minutes mode)."""
    live = [i for i in items if i.status != "dropped"]
    if not live:
        return 0
    lo = max(today - timedelta(days=CATCH_UP_WINDOW_DAYS), plan.start_date, min(i.added_on for i in live))
    hi = today - timedelta(days=1)
    if lo > hi:
        return 0
    active_days = sum(1 for n in range((hi - lo).days + 1) if is_active_day(plan, lo + timedelta(days=n)))
    if plan.pace_type == VIDEOS_PER_DAY:
        known = {i.id for i in items}
        expected = plan.pace_value * active_days
        done = sum(1 for v in views if v.item_id in known and lo <= v.local_date <= hi)
    else:
        expected = plan.pace_value * 60 * active_days
        done = sum(s.seconds for s in seconds if lo <= s.local_date <= hi)
    return max(0, expected - done)


# ---------------------------------------------------------------- one day

def evaluate_day(
    plan: PlanConfig,
    items: Sequence[ItemState],
    views_total: Mapping[str, int],
    views_today_by_item: Mapping[str, int],
    seconds_today: int,
    time_up: bool,
    today: date,
    backlog: int,
    extra: int,
    default_duration_seconds: int,
) -> FeedResult:
    """Feed for one day from pre-aggregated counters. `extra` is the already-capped catch-up (units of quota)."""
    videos = plan.pace_type == VIDEOS_PER_DAY
    views_today = sum(views_today_by_item.values())

    off = non_active_day_status(plan, today)
    if off is not None:
        return FeedResult(off, (), 0, views_today, 0 if videos else None, None if videos else 0, 0, (), ())

    quota = (plan.pace_value if videos else plan.pace_value * 60) + extra
    remaining_videos = max(0, quota - views_today) if videos else None
    remaining_seconds = None if videos else max(0, quota - seconds_today)
    remaining = remaining_videos if videos else remaining_seconds
    assert remaining is not None

    cls = classify(items, views_total, today)
    skipped_ids = tuple(i.id for i in sorted(cls.skipped, key=lambda i: (i.position, i.id)))
    dropped_ids = tuple(i.id for i in sorted(cls.dropped, key=lambda i: (i.position, i.id)))

    def result(status: str, feed: Sequence[FeedItem] = ()) -> FeedResult:
        return FeedResult(
            status, tuple(feed), quota, views_today, remaining_videos, remaining_seconds,
            backlog, skipped_ids, dropped_ids,
        )

    if not any(i.status != "dropped" for i in items):
        return result("empty")
    if not cls.pending:
        return result("all_done")
    if time_up:
        return result("time_up")

    eligible = sorted(
        (
            i for i in cls.pending
            if not (i.anchor_date is not None and i.anchor_date > today)
            and not (i.daily_cap is not None and views_today_by_item.get(i.id, 0) >= i.daily_cap)
        ),
        key=lambda i: sort_key(i, today),
    )
    if remaining <= 0 or not eligible:
        return result("done_today")

    for item in eligible:
        if item.required:  # required-first: only the first one, locked
            return result("ok", [FeedItem(item.id, item.video_id, True, True)])

    picked: list[FeedItem] = []
    accumulated = 0
    for item in eligible:
        if videos:
            if len(picked) >= remaining:
                break
        else:
            if accumulated >= remaining:
                break
            accumulated += item.duration_seconds if item.duration_seconds is not None else default_duration_seconds
        picked.append(FeedItem(item.id, item.video_id, False, False))
    return result("ok", picked)


def build_feed(
    plan: PlanConfig,
    items: Sequence[ItemState],
    views: Sequence[ViewRecord],
    seconds: Sequence[SecondsRecord],
    app_seconds: Sequence[AppSecondsRecord],
    limits: ChildLimits,
    today: date,
    default_duration_seconds: int = DEFAULT_DURATION_SECONDS,
) -> FeedResult:
    views_total, views_today_by_item = count_views(items, views, today)
    seconds_today = sum(s.seconds for s in seconds if s.local_date == today)
    app_today = sum(s.seconds for s in app_seconds if s.local_date == today)
    time_up = (
        limits.daily_app_minutes is not None
        and app_today >= limits.daily_app_minutes * 60 + limits.app_time_extra_seconds
    )
    backlog = compute_backlog(plan, items, views, seconds, today)
    cap = plan.max_extra_per_day if plan.pace_type == VIDEOS_PER_DAY else plan.max_extra_per_day * 60
    return evaluate_day(
        plan, items, views_total, views_today_by_item, seconds_today, time_up, today,
        backlog, min(backlog, cap), default_duration_seconds,
    )
