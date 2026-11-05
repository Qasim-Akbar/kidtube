import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.models import Child, Plan


def get_child_or_404(db: Session, family_id: uuid.UUID, child_id: uuid.UUID) -> Child:
    child = db.scalar(select(Child).where(Child.id == child_id, Child.family_id == family_id))
    if child is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Child not found")
    return child


def get_plan_or_404(db: Session, child: Child) -> Plan:
    plan = db.scalar(select(Plan).where(Plan.child_id == child.id))
    if plan is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "This child has no plan yet")
    return plan
