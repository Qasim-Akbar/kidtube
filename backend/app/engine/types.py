"""Plain frozen dataclasses for the pure plan engine.

No ORM, no DB, no timezones, no clock: the caller passes the child's local "today" as a date.
Every type round-trips through to_dict()/from_dict() using only strings, ints, bools, None and
lists (dates are ISO "YYYY-MM-DD" strings), because the Kotlin port is tested against the same
JSON vectors. Enum-like values are plain strings (same values as app/models/enums.py).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from typing import Any, Mapping

VIDEOS_PER_DAY = "videos_per_day"
MINUTES_PER_DAY = "minutes_per_day"
PACE_TYPES = (VIDEOS_PER_DAY, MINUTES_PER_DAY)
ON_MISS_VALUES = ("shift", "skip", "drop")
ITEM_STATUSES = ("active", "completed", "dropped")

DAY_STATUSES = ("scheduled", "day_off", "outside_dates")
FEED_STATUSES = ("ok", "outside_dates", "day_off", "empty", "all_done", "time_up", "done_today")

_ISO_DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


def _iso(d: date | None) -> str | None:
    return None if d is None else d.isoformat()


def _date(value: Any, field: str) -> date:
    if not isinstance(value, str) or not _ISO_DATE.fullmatch(value):
        raise ValueError(f"{field}: expected an ISO date string 'YYYY-MM-DD', got {value!r}")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise ValueError(f"{field}: invalid date {value!r}") from exc


def _opt_date(data: Mapping[str, Any], key: str) -> date | None:
    value = data.get(key)
    return None if value is None else _date(value, key)


def _int(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{field}: expected an integer, got {value!r}")
    return value


def _opt_int(data: Mapping[str, Any], key: str) -> int | None:
    value = data.get(key)
    return None if value is None else _int(value, key)


def _str(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{field}: expected a string, got {value!r}")
    return value


def _bool(value: Any, field: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{field}: expected a boolean, got {value!r}")
    return value


# ---------------------------------------------------------------- inputs

@dataclass(frozen=True)
class PlanConfig:
    pace_type: str
    pace_value: int
    active_weekdays: int  # bit (1 << weekday), Monday=0 .. Sunday=6
    max_extra_per_day: int  # videos in videos mode, MINUTES in minutes mode
    start_date: date
    end_date: date | None = None

    def __post_init__(self) -> None:
        if self.pace_type not in PACE_TYPES:
            raise ValueError(f"pace_type: unknown value {self.pace_type!r}")
        if self.pace_value < 0 or self.max_extra_per_day < 0:
            raise ValueError("pace_value and max_extra_per_day must be >= 0")
        if not 0 <= self.active_weekdays <= 127:
            raise ValueError("active_weekdays must be a 7-bit mask")

    def to_dict(self) -> dict[str, Any]:
        return {
            "pace_type": self.pace_type,
            "pace_value": self.pace_value,
            "active_weekdays": self.active_weekdays,
            "max_extra_per_day": self.max_extra_per_day,
            "start_date": _iso(self.start_date),
            "end_date": _iso(self.end_date),
        }

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> PlanConfig:
        return cls(
            pace_type=_str(d["pace_type"], "pace_type"),
            pace_value=_int(d["pace_value"], "pace_value"),
            active_weekdays=_int(d["active_weekdays"], "active_weekdays"),
            max_extra_per_day=_int(d["max_extra_per_day"], "max_extra_per_day"),
            start_date=_date(d["start_date"], "start_date"),
            end_date=_opt_date(d, "end_date"),
        )


@dataclass(frozen=True)
class ItemState:
    id: str
    video_id: str
    position: int
    required: bool
    view_target: int | None
    daily_cap: int | None
    anchor_date: date | None
    on_miss: str
    status: str
    duration_seconds: int | None
    added_on: date

    def __post_init__(self) -> None:
        if self.on_miss not in ON_MISS_VALUES:
            raise ValueError(f"on_miss: unknown value {self.on_miss!r}")
        if self.status not in ITEM_STATUSES:
            raise ValueError(f"status: unknown value {self.status!r}")

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "video_id": self.video_id,
            "position": self.position,
            "required": self.required,
            "view_target": self.view_target,
            "daily_cap": self.daily_cap,
            "anchor_date": _iso(self.anchor_date),
            "on_miss": self.on_miss,
            "status": self.status,
            "duration_seconds": self.duration_seconds,
            "added_on": _iso(self.added_on),
        }

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> ItemState:
        return cls(
            id=_str(d["id"], "id"),
            video_id=_str(d["video_id"], "video_id"),
            position=_int(d["position"], "position"),
            required=_bool(d["required"], "required"),
            view_target=_opt_int(d, "view_target"),
            daily_cap=_opt_int(d, "daily_cap"),
            anchor_date=_opt_date(d, "anchor_date"),
            on_miss=_str(d["on_miss"], "on_miss"),
            status=_str(d["status"], "status"),
            duration_seconds=_opt_int(d, "duration_seconds"),
            added_on=_date(d["added_on"], "added_on"),
        )


@dataclass(frozen=True)
class ViewRecord:
    item_id: str
    local_date: date

    def to_dict(self) -> dict[str, Any]:
        return {"item_id": self.item_id, "local_date": _iso(self.local_date)}

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> ViewRecord:
        return cls(_str(d["item_id"], "item_id"), _date(d["local_date"], "local_date"))


@dataclass(frozen=True)
class SecondsRecord:
    """watch_progress seconds (used by the minutes pace)."""

    local_date: date
    seconds: int

    def to_dict(self) -> dict[str, Any]:
        return {"local_date": _iso(self.local_date), "seconds": self.seconds}

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> SecondsRecord:
        return cls(_date(d["local_date"], "local_date"), _int(d["seconds"], "seconds"))


@dataclass(frozen=True)
class AppSecondsRecord:
    """app_session foreground seconds (used by the daily app-time limit)."""

    local_date: date
    seconds: int

    def to_dict(self) -> dict[str, Any]:
        return {"local_date": _iso(self.local_date), "seconds": self.seconds}

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> AppSecondsRecord:
        return cls(_date(d["local_date"], "local_date"), _int(d["seconds"], "seconds"))


@dataclass(frozen=True)
class ChildLimits:
    daily_app_minutes: int | None = None
    app_time_extra_seconds: int = 0

    def to_dict(self) -> dict[str, Any]:
        return {
            "daily_app_minutes": self.daily_app_minutes,
            "app_time_extra_seconds": self.app_time_extra_seconds,
        }

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> ChildLimits:
        return cls(_opt_int(d, "daily_app_minutes"), _int(d.get("app_time_extra_seconds", 0), "app_time_extra_seconds"))


# ---------------------------------------------------------------- outputs

@dataclass(frozen=True)
class FeedItem:
    item_id: str
    video_id: str
    locked: bool
    required: bool

    def to_dict(self) -> dict[str, Any]:
        return {"item_id": self.item_id, "video_id": self.video_id, "locked": self.locked, "required": self.required}

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> FeedItem:
        return cls(
            _str(d["item_id"], "item_id"), _str(d["video_id"], "video_id"),
            _bool(d["locked"], "locked"), _bool(d["required"], "required"),
        )


@dataclass(frozen=True)
class FeedResult:
    status: str
    items: tuple[FeedItem, ...]
    quota_today: int  # videos (videos mode) or seconds (minutes mode)
    views_today: int  # all views today, across items
    remaining_videos: int | None  # videos mode only
    remaining_seconds: int | None  # minutes mode only
    backlog: int  # same unit as quota_today; the raw (uncapped) catch-up backlog
    skipped_item_ids: tuple[str, ...]
    dropped_item_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        if self.status not in FEED_STATUSES:
            raise ValueError(f"status: unknown value {self.status!r}")

    def to_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "items": [i.to_dict() for i in self.items],
            "quota_today": self.quota_today,
            "views_today": self.views_today,
            "remaining_videos": self.remaining_videos,
            "remaining_seconds": self.remaining_seconds,
            "backlog": self.backlog,
            "skipped_item_ids": list(self.skipped_item_ids),
            "dropped_item_ids": list(self.dropped_item_ids),
        }

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> FeedResult:
        return cls(
            status=_str(d["status"], "status"),
            items=tuple(FeedItem.from_dict(i) for i in d["items"]),
            quota_today=_int(d["quota_today"], "quota_today"),
            views_today=_int(d["views_today"], "views_today"),
            remaining_videos=_opt_int(d, "remaining_videos"),
            remaining_seconds=_opt_int(d, "remaining_seconds"),
            backlog=_int(d["backlog"], "backlog"),
            skipped_item_ids=tuple(_str(i, "skipped_item_ids") for i in d["skipped_item_ids"]),
            dropped_item_ids=tuple(_str(i, "dropped_item_ids") for i in d["dropped_item_ids"]),
        )


@dataclass(frozen=True)
class ProjectedView:
    item_id: str
    video_id: str

    def to_dict(self) -> dict[str, Any]:
        return {"item_id": self.item_id, "video_id": self.video_id}

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> ProjectedView:
        return cls(_str(d["item_id"], "item_id"), _str(d["video_id"], "video_id"))


@dataclass(frozen=True)
class ProjectedDay:
    date: date
    status: str  # "scheduled" | "day_off" | "outside_dates"
    views: tuple[ProjectedView, ...]

    def __post_init__(self) -> None:
        if self.status not in DAY_STATUSES:
            raise ValueError(f"status: unknown value {self.status!r}")

    def to_dict(self) -> dict[str, Any]:
        return {"date": _iso(self.date), "status": self.status, "views": [v.to_dict() for v in self.views]}

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> ProjectedDay:
        return cls(_date(d["date"], "date"), _str(d["status"], "status"), tuple(ProjectedView.from_dict(v) for v in d["views"]))


@dataclass(frozen=True)
class Projection:
    days: tuple[ProjectedDay, ...]
    finish_date: date | None
    remaining_finite_views: int
    unscheduled_item_ids: tuple[str, ...]
    excluded_unlimited_item_ids: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "days": [d.to_dict() for d in self.days],
            "finish_date": _iso(self.finish_date),
            "remaining_finite_views": self.remaining_finite_views,
            "unscheduled_item_ids": list(self.unscheduled_item_ids),
            "excluded_unlimited_item_ids": list(self.excluded_unlimited_item_ids),
        }

    @classmethod
    def from_dict(cls, d: Mapping[str, Any]) -> Projection:
        return cls(
            days=tuple(ProjectedDay.from_dict(x) for x in d["days"]),
            finish_date=_opt_date(d, "finish_date"),
            remaining_finite_views=_int(d["remaining_finite_views"], "remaining_finite_views"),
            unscheduled_item_ids=tuple(_str(i, "unscheduled_item_ids") for i in d["unscheduled_item_ids"]),
            excluded_unlimited_item_ids=tuple(_str(i, "excluded_unlimited_item_ids") for i in d["excluded_unlimited_item_ids"]),
        )
