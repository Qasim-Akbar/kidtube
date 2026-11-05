from enum import Enum


class Platform(str, Enum):
    youtube = "youtube"
    facebook = "facebook"


class VideoStatus(str, Enum):
    pending = "pending"
    verified = "verified"
    unavailable = "unavailable"


class DeviceRole(str, Enum):
    parent = "parent"
    kid = "kid"


class PaceType(str, Enum):
    videos_per_day = "videos_per_day"
    minutes_per_day = "minutes_per_day"


class OnMiss(str, Enum):
    shift = "shift"
    skip = "skip"
    drop = "drop"


class PlanItemStatus(str, Enum):
    active = "active"
    completed = "completed"
    dropped = "dropped"


class EventType(str, Enum):
    view_complete = "view_complete"
    watch_progress = "watch_progress"
    app_session = "app_session"
    playback_error = "playback_error"
