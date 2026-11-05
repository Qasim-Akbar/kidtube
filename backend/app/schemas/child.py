import uuid

from pydantic import BaseModel, ConfigDict, Field


class ChildCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    avatar: str | None = Field(default=None, max_length=100)
    daily_app_minutes: int | None = Field(default=None, ge=1, le=1440)


class ChildUpdate(BaseModel):
    """PATCH body: only fields that are sent change (send null to clear avatar/limit)."""

    name: str | None = Field(default=None, min_length=1, max_length=100)
    avatar: str | None = Field(default=None, max_length=100)
    daily_app_minutes: int | None = Field(default=None, ge=1, le=1440)


class ChildOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    name: str
    avatar: str | None
    daily_app_minutes: int | None
