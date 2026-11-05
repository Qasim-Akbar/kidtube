import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.models.enums import DeviceRole


class PairCodeResponse(BaseModel):
    code: str
    expires_at: datetime


class PairRequest(BaseModel):
    code: str = Field(pattern=r"^\d{6}$")
    device_name: str = Field(min_length=1, max_length=100)


class PairResponse(BaseModel):
    device_id: uuid.UUID
    family_id: uuid.UUID
    device_token: str  # shown once; only its hash is stored


class DeviceOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    name: str
    role: DeviceRole
    paired_at: datetime | None
    revoked_at: datetime | None
