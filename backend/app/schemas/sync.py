import uuid
from datetime import date, datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

from app.models.enums import VideoStatus
from app.schemas.child import ChildOut
from app.schemas.event import EventResult
from app.schemas.plan import PlanOut
from app.schemas.video import VideoOut
from app.services.events import MAX_BATCH

MAX_PUSH_VIDEOS = 50


class HistoryViews(BaseModel):
    plan_item_id: uuid.UUID
    local_date: date
    count: int = Field(ge=1)


class HistorySeconds(BaseModel):
    local_date: date
    seconds: int


class ChildHistory(BaseModel):
    """What the plan engine needs to run offline, by the child's local date. Clients merge their own
    not-yet-acknowledged events on top (see SPEC section 9)."""

    views: list[HistoryViews] = Field(description="All-time view_complete counts per (item, day); only items still in the plan")
    watch_seconds: list[HistorySeconds] = Field(description="Last 30 family-local days (today and the 29 before)")
    app_seconds: list[HistorySeconds] = Field(description="Last 30 family-local days")
    time_extension_seconds: list[HistorySeconds] = Field(description="Last 30 family-local days")


class SnapshotChild(ChildOut):
    plan: PlanOut | None = Field(description="Plan config with ordered items; null when the child has no plan yet")
    history: ChildHistory


class SnapshotOut(BaseModel):
    server_time: datetime = Field(description="UTC; NOT part of snapshot_version")
    timezone: str = Field(description="Family timezone (IANA name)")
    snapshot_version: str = Field(description="Hash of everything except server_time; also sent as the ETag")
    children: list[SnapshotChild]
    videos: list[VideoOut]


class PushBody(BaseModel):
    """Items are validated one by one by the services, so one malformed item never fails the whole request."""

    videos: list[Any] = Field(default_factory=list, max_length=MAX_PUSH_VIDEOS, description="[{url}]")
    events: list[Any] = Field(default_factory=list, max_length=MAX_BATCH)


class PushVideoResult(BaseModel):
    index: int
    status: Literal["created", "existing", "rejected"]
    video_id: uuid.UUID | None = None
    video_status: VideoStatus | None = Field(default=None, description="pending | verified | unavailable (not for rejected)")
    reason: str | None = None
    retryable: bool = Field(
        description="rejected: always false today (validation / forbidden: resending cannot help). "
        "created: true when the video was saved but YouTube verification is still pending (oEmbed network trouble); "
        "the video exists, so do not resend it, call POST /videos/{id}/reverify later."
    )


class PushEventResult(EventResult):
    retryable: bool = Field(default=False, description="Always false: rejected events are permanent")


class PushResult(BaseModel):
    videos: list[PushVideoResult]
    events: list[PushEventResult]
