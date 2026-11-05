import uuid

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api._common import get_child_or_404, get_plan_or_404
from app.core.clock import NowFn, family_today, get_now
from app.core.db import get_db
from app.core.deps import FamilyAccess, get_current_parent, get_family_access
from app.models import Child, Parent, Plan, PlanItem, Video
from app.models.enums import PaceType, PlanItemStatus, VideoStatus
from app.schemas.plan import (
    PlanItemCreate, PlanItemOut, PlanItemPatch, PlanOut, PlanWrite, ReorderRequest, VideoSummary,
)

router = APIRouter(tags=["plans"])


def _ordered_items(db: Session, plan: Plan) -> list[PlanItem]:
    return list(db.scalars(select(PlanItem).where(PlanItem.plan_id == plan.id).order_by(PlanItem.position, PlanItem.id)))


def _item_out(item: PlanItem, video: Video) -> PlanItemOut:
    return PlanItemOut(
        id=item.id, video_id=item.video_id, position=item.position, required=item.required,
        view_target=item.view_target, daily_cap=item.daily_cap, watch_threshold=item.watch_threshold,
        anchor_date=item.anchor_date, on_miss=item.on_miss, status=item.status, added_on=item.added_on,
        video=VideoSummary(
            id=video.id, title=video.title, thumbnail_url=video.thumbnail_url, platform=video.platform,
            platform_video_id=video.platform_video_id, status=video.status, duration_seconds=video.duration_seconds,
        ),
    )


def _plan_out(db: Session, plan: Plan) -> PlanOut:
    rows = db.execute(
        select(PlanItem, Video).join(Video, Video.id == PlanItem.video_id)
        .where(PlanItem.plan_id == plan.id).order_by(PlanItem.position, PlanItem.id)
    ).all()
    return PlanOut(
        id=plan.id, child_id=plan.child_id, pace_type=plan.pace_type, pace_value=plan.pace_value,
        active_weekdays=plan.active_weekdays, max_extra_per_day=plan.max_extra_per_day, start_date=plan.start_date,
        end_date=plan.end_date, version=plan.version, items=[_item_out(i, v) for i, v in rows],
    )


def _renumber(items: list[PlanItem]) -> bool:
    changed = False
    for index, item in enumerate(items):
        if item.position != index:
            item.position = index
            changed = True
    return changed


def _item_for_parent(db: Session, parent: Parent, item_id: uuid.UUID) -> tuple[PlanItem, Plan]:
    row = db.execute(
        select(PlanItem, Plan).join(Plan, Plan.id == PlanItem.plan_id).join(Child, Child.id == Plan.child_id)
        .where(PlanItem.id == item_id, Child.family_id == parent.family_id)
    ).first()
    if row is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Plan item not found")
    return row[0], row[1]


def _has_active_item_for_video(db: Session, plan_id: uuid.UUID, video_id: uuid.UUID, exclude: uuid.UUID | None = None) -> bool:
    query = select(PlanItem.id).where(
        PlanItem.plan_id == plan_id, PlanItem.video_id == video_id, PlanItem.status == PlanItemStatus.active
    )
    if exclude is not None:
        query = query.where(PlanItem.id != exclude)
    return db.scalar(query) is not None


@router.get("/children/{child_id}/plan", response_model=PlanOut)
def get_plan(child_id: uuid.UUID, access: FamilyAccess = Depends(get_family_access), db: Session = Depends(get_db)) -> PlanOut:
    child = get_child_or_404(db, access.family_id, child_id)
    return _plan_out(db, get_plan_or_404(db, child))


@router.put("/children/{child_id}/plan", response_model=PlanOut)
def put_plan(
    child_id: uuid.UUID,
    body: PlanWrite,
    response: Response,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
) -> PlanOut:
    child = get_child_or_404(db, parent.family_id, child_id)
    max_extra = body.max_extra_per_day
    if max_extra is None:  # minutes mode counts max_extra in MINUTES, so the model default of 1 would be meaningless
        max_extra = body.pace_value if body.pace_type is PaceType.minutes_per_day else 1
    plan = db.scalar(select(Plan).where(Plan.child_id == child.id))
    if plan is None:
        plan = Plan(
            child_id=child.id, pace_type=body.pace_type, pace_value=body.pace_value,
            active_weekdays=body.active_weekdays, max_extra_per_day=max_extra, start_date=body.start_date,
            end_date=body.end_date, version=1,
        )
        db.add(plan)
        response.status_code = status.HTTP_201_CREATED
    else:
        new = {
            "pace_type": body.pace_type, "pace_value": body.pace_value, "active_weekdays": body.active_weekdays,
            "max_extra_per_day": max_extra, "start_date": body.start_date, "end_date": body.end_date,
        }
        if any(getattr(plan, k) != v for k, v in new.items()):
            for k, v in new.items():
                setattr(plan, k, v)
            plan.version += 1
    db.commit()
    return _plan_out(db, plan)


@router.post("/children/{child_id}/plan/items", response_model=PlanItemOut, status_code=status.HTTP_201_CREATED)
def add_plan_item(
    child_id: uuid.UUID,
    body: PlanItemCreate,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
) -> PlanItemOut:
    child = get_child_or_404(db, parent.family_id, child_id)
    plan = get_plan_or_404(db, child)
    video = db.scalar(select(Video).where(Video.id == body.video_id, Video.family_id == parent.family_id))
    if video is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Video not found")
    if video.status is VideoStatus.unavailable:
        raise HTTPException(422, "This video is unavailable")
    if _has_active_item_for_video(db, plan.id, video.id):
        raise HTTPException(status.HTTP_409_CONFLICT, "This video is already in the plan")

    item = PlanItem(
        plan_id=plan.id, video_id=video.id, position=0, required=body.required, view_target=body.view_target,
        daily_cap=body.daily_cap, watch_threshold=body.watch_threshold, anchor_date=body.anchor_date,
        on_miss=body.on_miss, status=PlanItemStatus.active,
        added_on=family_today(now(), child.family.timezone),
    )
    items = _ordered_items(db, plan)
    index = len(items) if body.position is None else min(body.position, len(items))
    items.insert(index, item)
    db.add(item)
    _renumber(items)
    plan.version += 1
    db.commit()
    return _item_out(item, video)


@router.patch("/plan-items/{item_id}", response_model=PlanItemOut)
def patch_plan_item(
    item_id: uuid.UUID,
    body: PlanItemPatch,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
) -> PlanItemOut:
    item, plan = _item_for_parent(db, parent, item_id)
    changes = body.model_dump(exclude_unset=True)
    for key in ("required", "on_miss", "watch_threshold", "status"):
        if key in changes and changes[key] is None:
            raise HTTPException(422, f"{key} cannot be null")
    if changes.get("status") is PlanItemStatus.active and _has_active_item_for_video(db, plan.id, item.video_id, item.id):
        raise HTTPException(status.HTTP_409_CONFLICT, "This video is already in the plan")
    if any(getattr(item, k) != v for k, v in changes.items()):
        for k, v in changes.items():
            setattr(item, k, v)
        plan.version += 1
    db.commit()
    return _item_out(item, db.get(Video, item.video_id))  # type: ignore[arg-type]


@router.delete("/plan-items/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_plan_item(
    item_id: uuid.UUID, parent: Parent = Depends(get_current_parent), db: Session = Depends(get_db)
) -> Response:
    item, plan = _item_for_parent(db, parent, item_id)
    db.delete(item)
    db.flush()
    _renumber(_ordered_items(db, plan))
    plan.version += 1
    db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.post("/children/{child_id}/plan/reorder", response_model=PlanOut)
def reorder_plan(
    child_id: uuid.UUID,
    body: ReorderRequest,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
) -> PlanOut:
    child = get_child_or_404(db, parent.family_id, child_id)
    plan = get_plan_or_404(db, child)
    items = _ordered_items(db, plan)
    by_id = {i.id: i for i in items}
    if len(body.item_ids) != len(set(body.item_ids)) or set(body.item_ids) != set(by_id):
        raise HTTPException(422, "item_ids must list every item of the plan exactly once")
    if _renumber([by_id[i] for i in body.item_ids]):
        plan.version += 1
    db.commit()
    return _plan_out(db, plan)
