from __future__ import annotations

from typing import TYPE_CHECKING

from sqlalchemy import String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, Timestamps, UUIDPk

if TYPE_CHECKING:
    from app.models.child import Child
    from app.models.device import Device
    from app.models.pairing_code import PairingCode
    from app.models.parent import Parent
    from app.models.video import Video

_CASCADE = {"cascade": "all, delete-orphan", "passive_deletes": True}


class Family(UUIDPk, Timestamps, Base):
    __tablename__ = "families"

    name: Mapped[str] = mapped_column(String(100))
    timezone: Mapped[str] = mapped_column(String(64), default="Asia/Karachi")

    parents: Mapped[list[Parent]] = relationship(back_populates="family", **_CASCADE)
    children: Mapped[list[Child]] = relationship(back_populates="family", **_CASCADE)
    devices: Mapped[list[Device]] = relationship(back_populates="family", **_CASCADE)
    videos: Mapped[list[Video]] = relationship(back_populates="family", **_CASCADE)
    pairing_codes: Mapped[list[PairingCode]] = relationship(**_CASCADE)
