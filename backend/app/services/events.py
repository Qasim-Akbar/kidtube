"""Validation and idempotent ingest for POST /events.

One bad event never fails the batch: every event gets its own result (accepted / duplicate / rejected + reason).
"""

from __future__ import annotations

import math
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import Child, Event
from app.models.enums import EventType

MAX_BATCH = 200
MAX_SESSION = timedelta(hours=24)
MAX_SECONDS = 24 * 3600
PARENT_ONLY_TYPES = frozenset({EventType.time_extension})  # a kid must never extend their own screen time
LOCAL_DATE_PAST_DAYS = 7
LOCAL_DATE_FUTURE_DAYS = 1


class Rejected(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class ValidEvent:
    id: uuid.UUID
    child_id: uuid.UUID
    type: EventType
    occurred_at: datetime
    local_date: date
    payload: dict[str, Any]
    plan_item_id: uuid.UUID | None


def _uuid(value: Any, field: str) -> uuid.UUID:
    if not isinstance(value, str):
        raise Rejected(f"invalid {field}")
    try:
        return uuid.UUID(value)
    except ValueError:
        raise Rejected(f"invalid {field}") from None


def _datetime(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise Rejected(f"invalid {field}")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00" if value.endswith("Z") else value)
    except ValueError:
        raise Rejected(f"invalid {field}") from None
    if parsed.tzinfo is None:
        raise Rejected(f"{field} must include a timezone offset")
    return parsed.astimezone(timezone.utc)


def _number(value: Any, field: str, *, minimum: float, maximum: float, exclusive_min: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise Rejected(f"invalid {field}")
    if value < minimum or (exclusive_min and value == minimum) or value > maximum:
        raise Rejected(f"{field} out of range")
    return value


def _payload(event_type: EventType, raw: Any) -> tuple[dict[str, Any], uuid.UUID | None]:
    """Validate the per-type payload shape and return a normalised copy (only known keys) plus plan_item_id."""
    if not isinstance(raw, dict):
        raise Rejected("payload must be an object")
    if event_type is EventType.view_complete:
        item_id = _uuid(raw.get("plan_item_id"), "payload.plan_item_id")
        pct = _number(raw.get("watched_pct"), "payload.watched_pct", minimum=0, maximum=1)
        return {"plan_item_id": str(item_id), "watched_pct": pct}, item_id
    if event_type is EventType.watch_progress:
        video_id = _uuid(raw.get("video_id"), "payload.video_id")
        seconds = _number(raw.get("seconds"), "payload.seconds", minimum=0, maximum=MAX_SECONDS)
        return {"video_id": str(video_id), "seconds": seconds}, None
    if event_type is EventType.app_session:
        start, end = _datetime(raw.get("start"), "payload.start"), _datetime(raw.get("end"), "payload.end")
        if end < start:
            raise Rejected("payload.end is before payload.start")
        if end - start > MAX_SESSION:
            raise Rejected("session longer than 24 hours")
        return {"start": start.isoformat(), "end": end.isoformat()}, None
    if event_type is EventType.playback_error:
        video_id = _uuid(raw.get("video_id"), "payload.video_id")
        code = raw.get("code")
        if isinstance(code, bool) or not isinstance(code, (int, str)) or (isinstance(code, str) and not 0 < len(code) <= 50):
            raise Rejected("invalid payload.code")
        return {"video_id": str(video_id), "code": code}, None
    if event_type is EventType.time_extension:
        seconds = _number(raw.get("seconds"), "payload.seconds", minimum=0, maximum=MAX_SECONDS, exclusive_min=True)
        return {"seconds": seconds}, None
    raise Rejected("unknown type")  # pragma: no cover


def validate_event(
    raw: Any, *, family_child_ids: set[uuid.UUID], today: date, is_parent: bool
) -> ValidEvent:
    if not isinstance(raw, dict):
        raise Rejected("event must be an object")
    event_id = _uuid(raw.get("id"), "id")
    child_id = _uuid(raw.get("child_id"), "child_id")
    if child_id not in family_child_ids:  # same answer for "no such child" and "another family's child"
        raise Rejected("unknown child")
    raw_type = raw.get("type")
    try:
        event_type = EventType(raw_type) if isinstance(raw_type, str) else None
    except ValueError:
        event_type = None
    if event_type is None:
        raise Rejected("unknown type")
    if event_type in PARENT_ONLY_TYPES and not is_parent:
        raise Rejected(f"{event_type.value} events may only be submitted by a parent")
    occurred_at = _datetime(raw.get("occurred_at"), "occurred_at")
    local = raw.get("local_date")
    if not isinstance(local, str) or len(local) != 10:
        raise Rejected("invalid local_date")
    try:
        local_date = date.fromisoformat(local)
    except ValueError:
        raise Rejected("invalid local_date") from None
    if not today - timedelta(days=LOCAL_DATE_PAST_DAYS) <= local_date <= today + timedelta(days=LOCAL_DATE_FUTURE_DAYS):
        raise Rejected("local_date outside the accepted range")
    payload, plan_item_id = _payload(event_type, raw.get("payload"))
    return ValidEvent(event_id, child_id, event_type, occurred_at, local_date, payload, plan_item_id)


def ingest_events(
    db: Session,
    raw_events: list[Any],
    *,
    family_id: uuid.UUID,
    today: date,
    is_parent: bool,
    device_id: uuid.UUID | None,
) -> list[dict[str, Any]]:
    family_child_ids = set(db.scalars(select(Child.id).where(Child.family_id == family_id)))
    results: list[dict[str, Any]] = []
    seen: set[uuid.UUID] = set()
    for index, raw in enumerate(raw_events):
        raw_id = raw.get("id") if isinstance(raw, dict) else None
        shown_id = raw_id if isinstance(raw_id, str) else None
        try:
            ev = validate_event(raw, family_child_ids=family_child_ids, today=today, is_parent=is_parent)
        except Rejected as exc:
            results.append({"index": index, "id": shown_id, "status": "rejected", "reason": exc.reason})
            continue
        if ev.id in seen or db.get(Event, ev.id) is not None:
            results.append({"index": index, "id": str(ev.id), "status": "duplicate", "reason": None})
            continue
        try:
            with db.begin_nested():  # a concurrent insert of the same id becomes "duplicate", not an error
                db.add(Event(
                    id=ev.id, child_id=ev.child_id, device_id=device_id, type=ev.type, occurred_at=ev.occurred_at,
                    local_date=ev.local_date, payload=ev.payload, plan_item_id=ev.plan_item_id,
                ))
        except IntegrityError:
            results.append({"index": index, "id": str(ev.id), "status": "duplicate", "reason": None})
            continue
        seen.add(ev.id)
        results.append({"index": index, "id": str(ev.id), "status": "accepted", "reason": None})
    db.commit()
    return results
