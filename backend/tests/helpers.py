from fastapi.testclient import TestClient

PASSWORD = "correct-horse"


def register(client: TestClient, email: str = "mum@example.com", password: str = PASSWORD, **extra):
    return client.post("/auth/register", json={"email": email, "password": password, **extra})


def login(client: TestClient, email: str = "mum@example.com", password: str = PASSWORD) -> dict:
    r = client.post("/auth/login", json={"email": email, "password": password})
    assert r.status_code == 200, r.text
    return r.json()


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def parent_session(client: TestClient, email: str = "mum@example.com") -> dict[str, str]:
    """Register + login; returns parent auth headers."""
    assert register(client, email).status_code == 201
    return bearer(login(client, email)["access_token"])


def new_code(client: TestClient, headers: dict[str, str]) -> str:
    r = client.post("/devices/pair-code", headers=headers)
    assert r.status_code == 201, r.text
    return r.json()["code"]


def pair(client: TestClient, parent_headers: dict[str, str], name: str = "Tablet") -> dict:
    r = client.post("/devices/pair", json={"code": new_code(client, parent_headers), "device_name": name})
    assert r.status_code == 201, r.text
    return r.json()


# ---------------------------------------------------------------- plan / event helpers
import uuid
from datetime import date, datetime

from sqlalchemy import Engine
from sqlalchemy.orm import Session

from app.models import Video
from app.models.enums import Platform, VideoStatus


def make_family(client: TestClient, email: str = "a@example.com", **register_extra):
    """Register + login. Returns (parent auth headers, family_id)."""
    r = register(client, email, **register_extra)
    assert r.status_code == 201, r.text
    return bearer(login(client, email)["access_token"]), r.json()["family_id"]


def make_child(client: TestClient, headers: dict[str, str], name: str = "Ali", **extra) -> str:
    r = client.post("/children", json={"name": name, **extra}, headers=headers)
    assert r.status_code == 201, r.text
    return r.json()["id"]


def make_video(
    engine: Engine, family_id: str, n: int, status: str = "verified", duration: int | None = None
) -> str:
    with Session(engine) as s:
        v = Video(
            family_id=uuid.UUID(family_id), platform=Platform.youtube, platform_video_id=f"vid{n:08d}",
            url=f"https://www.youtube.com/watch?v=vid{n:08d}", title=f"Video {n}",
            thumbnail_url=f"https://i.ytimg.com/vi/vid{n:08d}/hqdefault.jpg", status=VideoStatus(status),
            duration_seconds=duration,
        )
        s.add(v)
        s.commit()
        return str(v.id)


def set_video_status(engine: Engine, video_id: str, status: str) -> None:
    with Session(engine) as s:
        s.get(Video, uuid.UUID(video_id)).status = VideoStatus(status)
        s.commit()


def put_plan(client: TestClient, headers: dict[str, str], child_id: str, **kw):
    body = {"pace_type": "videos_per_day", "pace_value": 1, "active_weekdays": 127, "start_date": "2026-01-05", **kw}
    return client.put(f"/children/{child_id}/plan", json=body, headers=headers)


def add_item(client: TestClient, headers: dict[str, str], child_id: str, video_id: str, **kw):
    return client.post(f"/children/{child_id}/plan/items", json={"video_id": video_id, **kw}, headers=headers)


def make_plan(client, headers, child_id, video_ids: list[str], plan_kw: dict | None = None, item_kw: list[dict] | None = None):
    """Create a plan and add one item per video. Returns the item ids in order."""
    assert put_plan(client, headers, child_id, **(plan_kw or {})).status_code in (200, 201)
    ids = []
    for n, vid in enumerate(video_ids):
        r = add_item(client, headers, child_id, vid, **((item_kw or [{}] * len(video_ids))[n]))
        assert r.status_code == 201, r.text
        ids.append(r.json()["id"])
    return ids


def ev(child_id: str, type: str, payload: dict, local_date: str = "2026-01-05", id: str | None = None,
       occurred_at: str = "2026-01-05T10:00:00Z") -> dict:
    return {"id": id or str(uuid.uuid4()), "child_id": child_id, "type": type, "occurred_at": occurred_at,
            "local_date": local_date, "payload": payload}


def view_ev(child_id: str, item_id: str, local_date: str = "2026-01-05", **kw) -> dict:
    return ev(child_id, "view_complete", {"plan_item_id": item_id, "watched_pct": 0.95}, local_date, **kw)


def post_events(client: TestClient, headers: dict[str, str], events: list[dict]):
    return client.post("/events", json={"events": events}, headers=headers)


def statuses(response) -> list[str]:
    assert response.status_code == 200, response.text
    return [r["status"] for r in response.json()["results"]]


def feed(client: TestClient, headers: dict[str, str], child_id: str, **params):
    return client.get(f"/children/{child_id}/feed", headers=headers, params=params)


def feed_ids(response) -> list[str]:
    assert response.status_code == 200, response.text
    return [i["item_id"] for i in response.json()["items"]]


def relogin(client: TestClient, email: str = "a@example.com") -> dict[str, str]:
    """Fresh access token (they expire after 15 fake minutes when a test advances the clock)."""
    return bearer(login(client, email)["access_token"])
