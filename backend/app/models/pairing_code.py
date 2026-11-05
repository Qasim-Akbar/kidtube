from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import ForeignKey, String, Uuid
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, UTCDateTime, UUIDPk, utcnow


class PairingCode(UUIDPk, Base):
    """Single-use code a parent generates so a kid device can pair. Only a keyed hash is stored."""

    __tablename__ = "pairing_codes"

    family_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("families.id", ondelete="CASCADE"), index=True
    )
    created_by: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("parents.id", ondelete="SET NULL"))
    code_hash: Mapped[str] = mapped_column(String(64), index=True)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime)
    used_at: Mapped[datetime | None] = mapped_column(UTCDateTime)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
