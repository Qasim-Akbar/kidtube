from __future__ import annotations

import uuid
from datetime import date

from sqlalchemy import Boolean, Date, Float, ForeignKey, Index, Integer, Uuid
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, Timestamps, UUIDPk, str_enum, utcnow
from app.models.child import Child
from app.models.enums import OnMiss, PaceType, PlanItemStatus
from app.models.video import Video

ALL_WEEKDAYS = 0b1111111  # Mon=bit0 ... Sun=bit6


class Plan(UUIDPk, Timestamps, Base):
    """One plan per child (unique child_id)."""

    __tablename__ = "plans"

    child_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("children.id", ondelete="CASCADE"), unique=True
    )
    pace_type: Mapped[PaceType] = mapped_column(str_enum(PaceType), default=PaceType.videos_per_day)
    pace_value: Mapped[int] = mapped_column(Integer, default=1)
    active_weekdays: Mapped[int] = mapped_column(Integer, default=ALL_WEEKDAYS)
    max_extra_per_day: Mapped[int] = mapped_column(Integer, default=1)
    start_date: Mapped[date] = mapped_column(Date)
    end_date: Mapped[date | None] = mapped_column(Date)
    version: Mapped[int] = mapped_column(Integer, default=1)

    child: Mapped[Child] = relationship(back_populates="plan")
    items: Mapped[list[PlanItem]] = relationship(
        back_populates="plan",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="(PlanItem.position, PlanItem.id)",
    )


class PlanItem(UUIDPk, Timestamps, Base):
    __tablename__ = "plan_items"
    __table_args__ = (Index("ix_plan_items_plan_id_position", "plan_id", "position"),)

    plan_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("plans.id", ondelete="CASCADE"))
    video_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("videos.id", ondelete="CASCADE"), index=True
    )
    position: Mapped[int] = mapped_column(Integer)
    required: Mapped[bool] = mapped_column(Boolean, default=False)
    view_target: Mapped[int | None] = mapped_column(Integer)
    daily_cap: Mapped[int | None] = mapped_column(Integer)
    watch_threshold: Mapped[float] = mapped_column(Float, default=0.9)
    anchor_date: Mapped[date | None] = mapped_column(Date)
    on_miss: Mapped[OnMiss] = mapped_column(str_enum(OnMiss), default=OnMiss.shift)
    status: Mapped[PlanItemStatus] = mapped_column(str_enum(PlanItemStatus), default=PlanItemStatus.active)
    # The child's local date (family timezone) when the item joined the plan; the API sets it explicitly.
    # The fallback is only the UTC date and exists for code paths that build items without a timezone.
    added_on: Mapped[date] = mapped_column(Date, default=lambda: utcnow().date())

    plan: Mapped[Plan] = relationship(back_populates="items")
    video: Mapped[Video] = relationship(back_populates="plan_items")
