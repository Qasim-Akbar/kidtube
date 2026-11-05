from __future__ import annotations

import uuid
from typing import TYPE_CHECKING

from sqlalchemy import ForeignKey, Integer, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, Timestamps, UUIDPk
from app.models.family import Family

if TYPE_CHECKING:
    from app.models.event import Event
    from app.models.plan import Plan


class Child(UUIDPk, Timestamps, Base):
    __tablename__ = "children"

    family_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("families.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(100))
    avatar: Mapped[str | None] = mapped_column(String(100))
    daily_app_minutes: Mapped[int | None] = mapped_column(Integer)

    family: Mapped[Family] = relationship(back_populates="children")
    plan: Mapped[Plan | None] = relationship(
        back_populates="child", uselist=False, cascade="all, delete-orphan", passive_deletes=True
    )
    events: Mapped[list[Event]] = relationship(
        back_populates="child", cascade="all, delete-orphan", passive_deletes=True
    )
