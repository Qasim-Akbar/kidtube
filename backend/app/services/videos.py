"""Saving a shared link as a Video. Shared by POST /videos and POST /sync/push (no duplicated logic)."""

from __future__ import annotations

import uuid
from dataclasses import dataclass

import httpx
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import Video
from app.models.enums import Platform, VideoStatus
from app.services.oembed import OEmbedResult, check_youtube_video
from app.services.video_urls import VideoUrlError, parse_video_url

FACEBOOK_TITLE = "Facebook video"

__all__ = ["FACEBOOK_TITLE", "VideoUrlError", "SavedVideo", "apply_oembed", "get_family_video", "save_video"]


@dataclass(frozen=True)
class SavedVideo:
    video: Video
    created: bool


def get_family_video(db: Session, family_id: uuid.UUID, video_id: uuid.UUID) -> Video | None:
    return db.scalar(select(Video).where(Video.id == video_id, Video.family_id == family_id))


def _existing(db: Session, family_id: uuid.UUID, platform: Platform, platform_video_id: str) -> Video | None:
    return db.scalar(
        select(Video).where(
            Video.family_id == family_id, Video.platform == platform, Video.platform_video_id == platform_video_id
        )
    )


def apply_oembed(video: Video, result: OEmbedResult) -> None:
    if not result.conclusive:
        return  # YouTube was unreachable: never downgrade a known status because of a transient failure
    video.status = result.status
    if result.title is not None:
        video.title = result.title
    if result.thumbnail_url is not None:
        video.thumbnail_url = result.thumbnail_url


def save_video(
    db: Session, client: httpx.Client, *, family_id: uuid.UUID, added_by: uuid.UUID | None, url: str
) -> SavedVideo:
    """Parse, dedupe per family, verify (YouTube oEmbed) and commit. Raises VideoUrlError for a bad URL.

    Idempotent: a duplicate returns the existing video (created=False) with no outbound request.
    """
    parsed = parse_video_url(url)
    existing = _existing(db, family_id, parsed.platform, parsed.platform_video_id)
    if existing is not None:
        return SavedVideo(existing, created=False)

    video = Video(
        family_id=family_id, platform=parsed.platform, platform_video_id=parsed.platform_video_id,
        url=parsed.canonical_url, status=VideoStatus.pending, added_by=added_by,
    )
    if parsed.platform is Platform.youtube:
        apply_oembed(video, check_youtube_video(client, parsed.platform_video_id))
    else:
        video.title = FACEBOOK_TITLE  # no server-side verification is possible for Facebook
    db.add(video)
    try:
        db.commit()
    except IntegrityError:  # a concurrent request saved the same video first
        db.rollback()
        existing = _existing(db, family_id, parsed.platform, parsed.platform_video_id)
        if existing is None:
            raise
        return SavedVideo(existing, created=False)
    return SavedVideo(video, created=True)
