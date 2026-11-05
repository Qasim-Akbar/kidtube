import uuid
from datetime import date


from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.models.enums import OnMiss, PaceType, Platform, PlanItemStatus, VideoStatus


class PlanWrite(BaseModel):
    """PUT body (replaces the plan config). Omitted max_extra_per_day defaults to 1 (videos) or pace_value (minutes)."""

    model_config = ConfigDict(extra="forbid")

    pace_type: PaceType
    pace_value: int = Field(ge=1, le=1440)
    active_weekdays: int = Field(ge=1, le=127, description="Bitmask, Monday=bit 0 .. Sunday=bit 6")
    max_extra_per_day: int | None = Field(default=None, ge=0, le=1440, description="Videos, or MINUTES in minutes mode")
    start_date: date
    end_date: date | None = None

    @model_validator(mode="after")
    def _dates(self) -> "PlanWrite":
        if self.end_date is not None and self.end_date < self.start_date:
            raise ValueError("end_date must not be before start_date")
        return self


class VideoSummary(BaseModel):
    id: uuid.UUID
    title: str | None
    thumbnail_url: str | None
    platform: Platform
    platform_video_id: str
    status: VideoStatus
    duration_seconds: int | None


class PlanItemOut(BaseModel):
    id: uuid.UUID
    video_id: uuid.UUID
    position: int
    required: bool
    view_target: int | None
    daily_cap: int | None
    watch_threshold: float
    anchor_date: date | None
    on_miss: OnMiss
    status: PlanItemStatus
    added_on: date
    video: VideoSummary


class PlanOut(BaseModel):
    id: uuid.UUID
    child_id: uuid.UUID
    pace_type: PaceType
    pace_value: int
    active_weekdays: int
    max_extra_per_day: int
    start_date: date
    end_date: date | None
    version: int
    items: list[PlanItemOut]


class PlanItemCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    video_id: uuid.UUID
    required: bool = False
    view_target: int | None = Field(default=None, ge=1, le=10000)
    daily_cap: int | None = Field(default=None, ge=1, le=1000)
    anchor_date: date | None = None
    on_miss: OnMiss = OnMiss.shift
    watch_threshold: float = Field(default=0.9, gt=0, le=1)
    position: int | None = Field(default=None, ge=0, description="Zero-based index; omitted = append at the end")


class PlanItemPatch(BaseModel):
    """Only the fields that are sent change. view_target / daily_cap / anchor_date may be set to null."""

    model_config = ConfigDict(extra="forbid")

    required: bool | None = None
    view_target: int | None = Field(default=None, ge=1, le=10000)
    daily_cap: int | None = Field(default=None, ge=1, le=1000)
    anchor_date: date | None = None
    on_miss: OnMiss | None = None
    watch_threshold: float | None = Field(default=None, gt=0, le=1)
    status: PlanItemStatus | None = None


class ReorderRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    item_ids: list[uuid.UUID]


