import uuid
from typing import Annotated

from pydantic import BaseModel, BeforeValidator, EmailStr, Field


def _normalize_email(v: object) -> object:
    return v.strip().lower() if isinstance(v, str) else v


Email = Annotated[EmailStr, BeforeValidator(_normalize_email)]


class RegisterRequest(BaseModel):
    email: Email
    password: str = Field(min_length=8, max_length=128)
    family_name: str = Field(default="My Family", min_length=1, max_length=100)
    timezone: str | None = Field(default=None, max_length=64)


class RegisterResponse(BaseModel):
    parent_id: uuid.UUID
    family_id: uuid.UUID
    email: str


class LoginRequest(BaseModel):
    email: Annotated[str, BeforeValidator(_normalize_email), Field(min_length=1, max_length=255)]
    password: str = Field(min_length=1, max_length=128)


class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"


class RefreshRequest(BaseModel):
    refresh_token: str


class AccessTokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
