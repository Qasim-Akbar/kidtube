import uuid
from datetime import date, datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.models import Child, Device, Event, Family, Plan, PlanItem, Video
from app.models.enums import DeviceRole, EventType, PaceType, Platform

NOW = datetime(2026, 1, 5, 10, 0, tzinfo=timezone.utc)


def make_family(s: Session) -> Family:
    f = Family(name="Akbar")
    s.add(f)
    s.flush()
    return f


def make_video(s: Session, f: Family, vid: str = "abc123") -> Video:
    v = Video(family_id=f.id, platform=Platform.youtube, platform_video_id=vid, url=f"https://youtu.be/{vid}")
    s.add(v)
    s.flush()
    return v


def make_event(child: Child, device: Device | None = None, eid: uuid.UUID | None = None) -> Event:
    return Event(
        id=eid or uuid.uuid4(),
        child_id=child.id,
        device_id=device.id if device else None,
        type=EventType.view_complete,
        occurred_at=NOW,
        local_date=date(2026, 1, 5),
        payload={"watched_pct": 0.95},
    )


def count(s: Session, model: type) -> int:
    return s.scalar(select(func.count()).select_from(model)) or 0


def test_two_children_with_separate_plans(session: Session) -> None:
    f = make_family(session)
    v1, v2 = make_video(session, f, "v1"), make_video(session, f, "v2")
    a, b = Child(family_id=f.id, name="A"), Child(family_id=f.id, name="B", daily_app_minutes=60)
    session.add_all([a, b])
    session.flush()
    pa = Plan(child_id=a.id, start_date=date(2026, 1, 1), pace_value=1)
    pb = Plan(child_id=b.id, start_date=date(2026, 1, 1), pace_type=PaceType.minutes_per_day, pace_value=30)
    pa.items = [PlanItem(video_id=v1.id, position=0, required=True)]
    pb.items = [PlanItem(video_id=v1.id, position=0), PlanItem(video_id=v2.id, position=1)]
    session.add_all([pa, pb])
    session.commit()

    session.expire_all()
    assert a.plan.pace_value == 1 and len(a.plan.items) == 1
    assert b.plan.pace_type is PaceType.minutes_per_day and [i.position for i in b.plan.items] == [0, 1]
    assert a.plan.id != b.plan.id
    assert a.plan.items[0].watch_threshold == 0.9 and a.plan.max_extra_per_day == 1

    session.add(Plan(child_id=a.id, start_date=date(2026, 2, 1)))
    with pytest.raises(IntegrityError):
        session.flush()


def test_video_dedupe_per_family(session: Session) -> None:
    f1, f2 = make_family(session), make_family(session)
    make_video(session, f1, "dup")
    make_video(session, f2, "dup")  # other family is fine
    session.add(Video(family_id=f1.id, platform=Platform.facebook, platform_video_id="dup", url="https://fb.watch/dup"))
    session.flush()  # other platform is fine
    session.add(Video(family_id=f1.id, platform=Platform.youtube, platform_video_id="dup", url="https://youtu.be/dup"))
    with pytest.raises(IntegrityError):
        session.flush()


def test_event_client_uuid_unique(session: Session) -> None:
    f = make_family(session)
    c = Child(family_id=f.id, name="A")
    session.add(c)
    session.flush()
    eid = uuid.uuid4()
    session.add(make_event(c, eid=eid))
    session.commit()
    session.add(make_event(c, eid=eid))
    with pytest.raises(IntegrityError):
        session.flush()


def test_cascade_delete_family(session: Session) -> None:
    f = make_family(session)
    v = make_video(session, f)
    c = Child(family_id=f.id, name="A")
    d = Device(family_id=f.id, name="tab", role=DeviceRole.kid)
    session.add_all([c, d])
    session.flush()
    plan = Plan(child_id=c.id, start_date=date(2026, 1, 1))
    plan.items = [PlanItem(video_id=v.id, position=0)]
    session.add_all([plan, make_event(c, d)])
    session.commit()

    session.delete(f)
    session.commit()
    for model in (Family, Child, Device, Video, Plan, PlanItem, Event):
        assert count(session, model) == 0, model.__name__


def test_deleting_device_and_video_side_effects(session: Session) -> None:
    f = make_family(session)
    v = make_video(session, f)
    c = Child(family_id=f.id, name="A")
    d = Device(family_id=f.id, name="tab", role=DeviceRole.kid)
    session.add_all([c, d])
    session.flush()
    plan = Plan(child_id=c.id, start_date=date(2026, 1, 1))
    plan.items = [PlanItem(video_id=v.id, position=0)]
    ev = make_event(c, d)
    session.add_all([plan, ev])
    session.commit()

    session.delete(d)
    session.commit()
    session.refresh(ev)
    assert ev.device_id is None and count(session, Event) == 1  # history kept

    session.delete(v)
    session.commit()
    assert count(session, PlanItem) == 0 and count(session, Plan) == 1


def test_datetimes_utc_and_enums_are_strings(session: Session) -> None:
    f = make_family(session)
    c = Child(family_id=f.id, name="A")
    session.add(c)
    session.flush()
    ev = make_event(c)
    ev.occurred_at = datetime(2026, 1, 5, 15, 0, tzinfo=timezone(timedelta(hours=5)))  # PKT
    session.add(ev)
    session.commit()
    session.expire_all()

    loaded = session.get(Event, ev.id)
    assert loaded.occurred_at.tzinfo is not None
    assert loaded.occurred_at == datetime(2026, 1, 5, 10, 0, tzinfo=timezone.utc)
    assert loaded.created_at.tzinfo is not None and f.created_at.tzinfo is not None
    assert session.execute(text("select type from events")).scalar_one() == "view_complete"

    session.add(make_event(c))
    session.flush()
    bad = make_event(c)
    bad.occurred_at = datetime(2026, 1, 5, 10, 0)  # naive
    session.add(bad)
    with pytest.raises(Exception):
        session.flush()
