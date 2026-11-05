import uuid

import httpx
from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.clock import NowFn, get_now
from app.core.db import get_db
from app.core.deps import FamilyAccess, get_current_parent, get_family_access, get_http_client
from app.models import Parent, Plan, PlanItem, Video
from app.models.enums import Platform
from app.schemas.video import VideoCreate, VideoOut
from app.services.oembed import check_youtube_video
from app.services.plan_views import ordered_items, renumber
from app.services.videos import VideoUrlError, apply_oembed, get_family_video, save_video

router = APIRouter(prefix="/videos", tags=["videos"])

def _get_video(db: Session, family_id: uuid.UUID, video_id: uuid.UUID) -> Video:
    video = get_family_video(db, family_id, video_id)
    if video is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Video not found")
    return video


@router.post("", response_model=VideoOut, status_code=status.HTTP_201_CREATED)
def create_video(
    body: VideoCreate,
    response: Response,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
    client: httpx.Client = Depends(get_http_client),
) -> Video:
    try:
        saved = save_video(db, client, family_id=parent.family_id, added_by=parent.id, url=body.url)
    except VideoUrlError as exc:
        raise HTTPException(422, str(exc)) from None
    if not saved.created:  # idempotent: offline sync retries land here
        response.status_code = status.HTTP_200_OK
    return saved.video


@router.get("", response_model=list[VideoOut])
def list_videos(access: FamilyAccess = Depends(get_family_access), db: Session = Depends(get_db)) -> list[Video]:
    return list(db.scalars(select(Video).where(Video.family_id == access.family_id).order_by(Video.added_at, Video.id)))


@router.delete("/{video_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_video(
    video_id: uuid.UUID, parent: Parent = Depends(get_current_parent), db: Session = Depends(get_db)
) -> Response:
    video = _get_video(db, parent.family_id, video_id)
    plans = list(db.scalars(select(Plan).join(PlanItem, PlanItem.plan_id == Plan.id).where(PlanItem.video_id == video.id).distinct()))
    db.delete(video)  # plan items referencing it cascade
    db.flush()
    for plan in plans:  # keep positions dense and let clients see that the plan changed
        renumber(ordered_items(db, plan))
        plan.version += 1
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
        apply_oembed(video, check_youtube_video(client, video.platform_video_id))
        db.commit()
    return video
