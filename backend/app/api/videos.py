import uuid

import httpx
from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.core.clock import NowFn, get_now
from app.core.db import get_db
from app.core.deps import FamilyAccess, get_current_parent, get_family_access, get_http_client
from app.models import Parent, Video
from app.models.enums import Platform, VideoStatus
from app.schemas.video import VideoCreate, VideoOut
from app.services.oembed import OEmbedResult, check_youtube_video
from app.services.video_urls import VideoUrlError, parse_video_url

router = APIRouter(prefix="/videos", tags=["videos"])

FACEBOOK_TITLE = "Facebook video"


def _get_video(db: Session, family_id: uuid.UUID, video_id: uuid.UUID) -> Video:
    video = db.scalar(select(Video).where(Video.id == video_id, Video.family_id == family_id))
    if video is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Video not found")
    return video


def _existing(db: Session, family_id: uuid.UUID, platform: Platform, platform_video_id: str) -> Video | None:
    return db.scalar(
        select(Video).where(
            Video.family_id == family_id, Video.platform == platform, Video.platform_video_id == platform_video_id
        )
    )


def _apply(video: Video, result: OEmbedResult) -> None:
    if not result.conclusive:
        return  # YouTube was unreachable: never downgrade a known status because of a transient failure
    video.status = result.status
    if result.title is not None:
        video.title = result.title
    if result.thumbnail_url is not None:
        video.thumbnail_url = result.thumbnail_url


@router.post("", response_model=VideoOut, status_code=status.HTTP_201_CREATED)
def create_video(
    body: VideoCreate,
    response: Response,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
    client: httpx.Client = Depends(get_http_client),
) -> Video:
    try:
        parsed = parse_video_url(body.url)
    except VideoUrlError as exc:
        raise HTTPException(422, str(exc)) from None

    existing = _existing(db, parent.family_id, parsed.platform, parsed.platform_video_id)
    if existing is not None:  # idempotent: offline sync retries land here, with no outbound request
        response.status_code = status.HTTP_200_OK
        return existing

    video = Video(
        family_id=parent.family_id, platform=parsed.platform, platform_video_id=parsed.platform_video_id,
        url=parsed.canonical_url, status=VideoStatus.pending, added_by=parent.id,
    )
    if parsed.platform is Platform.youtube:
        _apply(video, check_youtube_video(client, parsed.platform_video_id))
    else:
        video.title = FACEBOOK_TITLE  # no server-side verification is possible for Facebook
    db.add(video)
    try:
        db.commit()
    except IntegrityError:  # a concurrent request saved the same video first
        db.rollback()
        existing = _existing(db, parent.family_id, parsed.platform, parsed.platform_video_id)
        if existing is None:
            raise
        response.status_code = status.HTTP_200_OK
        return existing
    return video


@router.get("", response_model=list[VideoOut])
def list_videos(access: FamilyAccess = Depends(get_family_access), db: Session = Depends(get_db)) -> list[Video]:
    return list(db.scalars(select(Video).where(Video.family_id == access.family_id).order_by(Video.added_at, Video.id)))


@router.delete("/{video_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_video(
    video_id: uuid.UUID, parent: Parent = Depends(get_current_parent), db: Session = Depends(get_db)
) -> Response:
    db.delete(_get_video(db, parent.family_id, video_id))  # plan items referencing it cascade
    db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/{video_id}/reverify", response_model=VideoOut)
def reverify_video(
    video_id: uuid.UUID,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
    client: httpx.Client = Depends(get_http_client),
) -> Video:
    video = _get_video(db, parent.family_id, video_id)
    if video.platform is Platform.youtube:
        _apply(video, check_youtube_video(client, video.platform_video_id))
        db.commit()
    return video
