import uuid
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

from app.models.enums import VideoStatus
from app.schemas.child import ChildOut
from app.schemas.event import EventResult
from app.schemas.plan import PlanOut
from app.schemas.video import VideoOut
from app.services.events import MAX_BATCH

MAX_PUSH_VIDEOS = 50


class SnapshotChild(ChildOut):
    plan: PlanOut | None = Field(description="Plan config with ordered items; null when the child has no plan yet")


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
