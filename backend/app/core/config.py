from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_JWT_SECRET = "change-me"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: str = "dev"
    database_url: str = "sqlite:///./kidtube.db"
    jwt_secret: str = DEFAULT_JWT_SECRET
    access_token_minutes: int = 15
    refresh_token_days: int = 30
    default_timezone: str = "Asia/Karachi"
    allow_registration: bool = True
    pair_code_minutes: int = 10
    pair_max_failures: int = 5
    pair_failure_window_minutes: int = 10
    login_max_failures: int = 5
    login_failure_window_minutes: int = 10
    trust_proxy_headers: bool = False


def validate_settings(settings: Settings) -> None:
    """Refuse to start with an insecure configuration outside dev."""
    if settings.app_env != "dev" and settings.jwt_secret == DEFAULT_JWT_SECRET:
        raise RuntimeError("JWT_SECRET must be changed from the default when APP_ENV is not 'dev'")


@lru_cache
def get_settings() -> Settings:
    return Settings()
