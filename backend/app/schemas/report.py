import uuid
from datetime import date as Date
from typing import Literal

from pydantic import BaseModel, Field

from app.models.enums import PaceType, PlanItemStatus


class ReportDay(BaseModel):
    date: Date
    watch_seconds: int
    app_seconds: int
    browsing_seconds: int = Field(description="max(0, app_seconds - watch_seconds)")
    views_completed: int


class ReportTotals(BaseModel):
    watch_seconds: int
    app_seconds: int
    browsing_seconds: int = Field(description="max(0, total app_seconds - total watch_seconds); not the sum of the daily values")
    views_completed: int


class TopVideo(BaseModel):
    video_id: uuid.UUID
    title: str | None
    thumbnail_url: str | None
    views: int
    watch_seconds: int


class ItemCompletion(BaseModel):
    item_id: uuid.UUID
    video_id: uuid.UUID
    title: str | None
    required: bool
    status: PlanItemStatus
    view_target: int | None
    views_total: int = Field(description="All-time views up to today (not limited to the range)")
    views_in_range: int
    completed: bool


class PlaybackErrorCount(BaseModel):
    video_id: uuid.UUID
    title: str | None
    code: str
    count: int


class PlannedVsWatched(BaseModel):
    pace_type: PaceType
    unit: Literal["videos", "seconds"] = Field(description="videos in videos_per_day mode, seconds in minutes_per_day mode")
    window_start: Date | None = Field(description="Range clipped to plan dates and today; null when nothing is counted")
    window_end: Date | None
    active_days: int
    expected: int
    actual: int


class PlanProgress(BaseModel):
    as_of: Date = Field(description="Family-local today; the projection always starts from today, whatever the range")
    finish_date: Date | None
    remaining_finite_views: int


class ReportOut(BaseModel):
    child_id: uuid.UUID
    range: Literal["day", "week", "month"]
    date: Date = Field(description="The date the range was computed around")
    start: Date
    end: Date
    days: list[ReportDay]
    totals: ReportTotals
    top_videos: list[TopVideo]
    per_video: list[ItemCompletion]
    playback_errors: list[PlaybackErrorCount]
    planned_vs_watched: PlannedVsWatched | None
    plan_progress: PlanProgress | None
