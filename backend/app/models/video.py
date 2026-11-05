from __future__ import annotations

import uuid
from datetime import datetime
from typing import TYPE_CHECKING

from sqlalchemy import ForeignKey, Integer, String, UniqueConstraint, Uuid
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, Timestamps, UUIDPk, UTCDateTime, str_enum, utcnow
from app.models.enums import Platform, VideoStatus
from app.models.family import Family

if TYPE_CHECKING:
    from app.models.plan import PlanItem


class Video(UUIDPk, Timestamps, Base):
    __tablename__ = "videos"
    __table_args__ = (
        UniqueConstraint("family_id", "platform", "platform_video_id", name="uq_videos_family_platform_video"),
    )

    family_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("families.id", ondelete="CASCADE"), index=True
    )
    platform: Mapped[Platform] = mapped_column(str_enum(Platform))
    platform_video_id: Mapped[str] = mapped_column(String(64))
    url: Mapped[str] = mapped_column(String(2048))
    title: Mapped[str | None] = mapped_column(String(500))
    thumbnail_url: Mapped[str | None] = mapped_column(String(2048))
    duration_seconds: Mapped[int | None] = mapped_column(Integer)
    status: Mapped[VideoStatus] = mapped_column(str_enum(VideoStatus), default=VideoStatus.pending)
    added_by: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("parents.id", ondelete="SET NULL"))
    added_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)

    family: Mapped[Family] = relationship(back_populates="videos")
    plan_items: Mapped[list[PlanItem]] = relationship(
        back_populates="video", cascade="all, delete-orphan", passive_deletes=True
    )
