from typing import Any, Literal

from pydantic import BaseModel, Field

from app.services.events import MAX_BATCH


class EventBatch(BaseModel):
    """Events are validated one by one by the service, so a malformed event never fails the whole request."""

    events: list[Any] = Field(max_length=MAX_BATCH)


class EventResult(BaseModel):
    index: int
    id: str | None
    status: Literal["accepted", "duplicate", "rejected"]
    reason: str | None = None


class EventBatchResult(BaseModel):
    results: list[EventResult]
