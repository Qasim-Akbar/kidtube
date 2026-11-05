import uuid
from datetime import date as Date
from typing import Any

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api._common import get_child_or_404
from app.core.clock import NowFn, family_today, get_now
from app.core.db import get_db
from app.core.deps import get_current_parent
from app.models import Parent, Plan
from app.schemas.report import ReportOut
from app.services.reports import Range, build_report

router = APIRouter(prefix="/reports", tags=["reports"])


@router.get("/children/{child_id}", response_model=ReportOut)
def child_report(
    child_id: uuid.UUID,
    range: Range = Query(description="day | week (Monday..Sunday) | month (calendar month)"),  # noqa: A002
    on_date: Date | None = Query(default=None, alias="date", description="Defaults to the family-local today"),
    parent: Parent = Depends(get_current_parent),
    db: Session = Depends(get_db),
    now: NowFn = Depends(get_now),
) -> dict[str, Any]:
    child = get_child_or_404(db, parent.family_id, child_id)
    today = family_today(now(), child.family.timezone)
    plan = db.scalar(select(Plan).where(Plan.child_id == child.id))
    return build_report(db, child, plan, range, on_date or today, today)
