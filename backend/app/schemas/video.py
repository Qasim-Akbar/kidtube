import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.models.enums import Platform, VideoStatus


class VideoCreate(BaseModel):
    url: str = Field(min_length=1, max_length=2048)


class VideoOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    platform: Platform
    platform_video_id: str
    url: str
    title: str | None
    thumbnail_url: str | None
    duration_seconds: int | None
    status: VideoStatus
    added_by: uuid.UUID | None
    added_at: datetime
