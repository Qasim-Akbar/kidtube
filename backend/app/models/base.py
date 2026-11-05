import uuid
from datetime import datetime, timezone
from enum import Enum as PyEnum
from typing import Any

from sqlalchemy import DateTime, Enum, Uuid
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator

from app.core.clock import now as utcnow  # model defaults share the one clock (no direct datetime.now)


class UTCDateTime(TypeDecorator):
    """Timezone-aware UTC datetime that also round-trips on SQLite (which drops tzinfo)."""

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect: Any) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("naive datetime not allowed; use timezone-aware UTC")
        return value.astimezone(timezone.utc)

    def process_result_value(self, value: datetime | None, dialect: Any) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)


def str_enum(enum_cls: type[PyEnum]) -> Enum:
    """Store a Python Enum as a plain VARCHAR (no native/Postgres ENUM type, no CHECK)."""
    return Enum(
        enum_cls,
        native_enum=False,
        length=20,
        create_constraint=False,
        values_callable=lambda e: [m.value for m in e],
    )


class Base(DeclarativeBase):
    pass


class UUIDPk:
    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid.uuid4)


class Timestamps:
    created_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime, default=utcnow, onupdate=utcnow)
