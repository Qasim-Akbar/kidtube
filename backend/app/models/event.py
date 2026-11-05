from __future__ import annotations

import uuid
from datetime import date, datetime
from typing import Any

from sqlalchemy import JSON, Date, ForeignKey, Index, Uuid
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, UTCDateTime, str_enum, utcnow
from app.models.child import Child
from app.models.enums import EventType


class Event(Base):
    """Append-only log. `id` is the client-generated UUID (idempotency key).

    No updated_at: rows are never modified (enforced at the service layer).
    """

    __tablename__ = "events"
    __table_args__ = (
        Index("ix_events_child_id_local_date", "child_id", "local_date"),
        Index("ix_events_child_id_type_occurred_at", "child_id", "type", "occurred_at"),
        # views per plan item per day (feed loader): GROUP BY plan_item_id, local_date
        Index("ix_events_child_id_type_plan_item_id_local_date", "child_id", "type", "plan_item_id", "local_date"),
        # per-day sums of watch_progress / time_extension seconds
        Index("ix_events_child_id_type_local_date", "child_id", "type", "local_date"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True)
    child_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("children.id", ondelete="CASCADE"))
    device_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("devices.id", ondelete="SET NULL"))
    type: Mapped[EventType] = mapped_column(str_enum(EventType))
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime)
    local_date: Mapped[date] = mapped_column(Date)
    payload: Mapped[dict[str, Any]] = mapped_column(JSON, default=dict)
    # Denormalised from payload["plan_item_id"] for view_complete events so views can be aggregated in plain SQL.
    plan_item_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, nullable=True)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    child: Mapped[Child] = relationship(back_populates="events")
