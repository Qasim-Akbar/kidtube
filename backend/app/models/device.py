from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import ForeignKey, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, Timestamps, UUIDPk, UTCDateTime, str_enum
from app.models.enums import DeviceRole
from app.models.family import Family


class Device(UUIDPk, Timestamps, Base):
    __tablename__ = "devices"

    family_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("families.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(100))
    role: Mapped[DeviceRole] = mapped_column(str_enum(DeviceRole))
    token_hash: Mapped[str | None] = mapped_column(String(255), unique=True)
    paired_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(UTCDateTime)

    family: Mapped[Family] = relationship(back_populates="devices")
