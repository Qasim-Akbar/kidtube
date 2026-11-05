import uuid

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.db import get_db
from app.core.deps import FamilyAccess, get_current_parent, get_family_access
from app.models import Child, Parent
from app.schemas.child import ChildCreate, ChildOut, ChildUpdate

router = APIRouter(prefix="/children", tags=["children"])


@router.post("", response_model=ChildOut, status_code=status.HTTP_201_CREATED)
def create_child(
    body: ChildCreate, parent: Parent = Depends(get_current_parent), db: Session = Depends(get_db)
) -> Child:
    child = Child(family_id=parent.family_id, **body.model_dump())
    db.add(child)
    db.commit()
    return child


@router.get("", response_model=list[ChildOut])
def list_children(access: FamilyAccess = Depends(get_family_access), db: Session = Depends(get_db)) -> list[Child]:
    return list(
        db.scalars(select(Child).where(Child.family_id == access.family_id).order_by(Child.created_at, Child.id))
    )


@router.patch("/{child_id}", response_model=ChildOut)
def update_child(
    child_id: uuid.UUID,
    body: ChildUpdate,
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
) -> Child:
    child = db.scalar(select(Child).where(Child.id == child_id, Child.family_id == parent.family_id))
    if child is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Child not found")
    changes = body.model_dump(exclude_unset=True)
    if "name" in changes and changes["name"] is None:
        raise HTTPException(422, "name cannot be null")
    for field, value in changes.items():
        setattr(child, field, value)
    db.commit()
    return child
