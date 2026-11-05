import uuid
from datetime import date as Date

from pydantic import BaseModel, Field

from app.models.enums import PaceType, Platform


class FeedItemOut(BaseModel):
    item_id: uuid.UUID
    video_id: uuid.UUID
    locked: bool = Field(description="True when this is the single required item the child must watch next")
    required: bool
    title: str | None
    thumbnail_url: str | None
    platform: Platform
    platform_video_id: str
    duration_seconds: int | None


class FeedOut(BaseModel):
    date: Date = Field(description="The child's local date (family timezone) the feed was computed for")
    pace_type: PaceType
    status: str = Field(
        description="ok | outside_dates | day_off | empty | all_done | time_up | done_today"
    )
    items: list[FeedItemOut]
    quota_today: int = Field(description="UNITS DEPEND ON pace_type: videos in videos_per_day mode, SECONDS in minutes_per_day mode")
    views_today: int
    remaining_videos: int | None = Field(description="videos_per_day mode only")
    remaining_seconds: int | None = Field(description="minutes_per_day mode only")
    backlog: int = Field(description="Raw catch-up backlog; same units as quota_today (videos, or seconds in minutes mode)")
    skipped_item_ids: list[uuid.UUID]
    dropped_item_ids: list[uuid.UUID]
    unavailable_item_ids: list[uuid.UUID] = Field(
        description="Active plan items whose video is unavailable; they are not offered and not passed to the engine"
    )


class ProjectedViewOut(BaseModel):
    item_id: uuid.UUID
    video_id: uuid.UUID
    title: str | None
    thumbnail_url: str | None


class ProjectedDayOut(BaseModel):
    date: Date
    status: str = Field(description="scheduled | day_off | outside_dates")
    views: list[ProjectedViewOut]


class ProjectionOut(BaseModel):
    today: Date
    pace_type: PaceType
    days: list[ProjectedDayOut]
    finish_date: Date | None
    remaining_finite_views: int
    unscheduled_item_ids: list[uuid.UUID]
    excluded_unlimited_item_ids: list[uuid.UUID]
    unavailable_item_ids: list[uuid.UUID]
