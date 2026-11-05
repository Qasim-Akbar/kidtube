"""Run the real Alembic migration on a SQLite file that already holds data from the previous revision."""

import os
import sqlite3
import subprocess
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
PREVIOUS = "5e8b9df77b8c"


def alembic(db: Path, *args: str) -> None:
    env = {**os.environ, "DATABASE_URL": f"sqlite:///{db}"}
    result = subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


def hexid(n: int) -> str:
    return f"{n:032x}"


def test_added_on_is_backfilled_from_created_at_and_downgrade_works(tmp_path: Path) -> None:
    db = tmp_path / "m.db"
    alembic(db, "upgrade", PREVIOUS)
    con = sqlite3.connect(db)
    ts = "2026-01-05 22:30:00.000000"
    con.execute("insert into families (id, name, timezone, created_at, updated_at) values (?,?,?,?,?)", (hexid(1), "F", "Asia/Karachi", ts, ts))
    con.execute("insert into children (id, family_id, name, created_at, updated_at) values (?,?,?,?,?)", (hexid(2), hexid(1), "Ali", ts, ts))
    con.execute(
        "insert into videos (id, family_id, platform, platform_video_id, url, status, added_at, created_at, updated_at) "
        "values (?,?,?,?,?,?,?,?,?)", (hexid(3), hexid(1), "youtube", "abcdefghijk", "u", "verified", ts, ts, ts))
    con.execute(
        "insert into plans (id, child_id, pace_type, pace_value, active_weekdays, max_extra_per_day, start_date, version, created_at, updated_at) "
        "values (?,?,?,?,?,?,?,?,?,?)", (hexid(4), hexid(2), "videos_per_day", 1, 127, 1, "2026-01-01", 1, ts, ts))
    for n, created in ((5, "2026-01-05 22:30:00.000000"), (6, "2026-02-01 00:00:01.500000")):
        con.execute(
            "insert into plan_items (id, plan_id, video_id, position, required, watch_threshold, on_miss, status, created_at, updated_at) "
            "values (?,?,?,?,?,?,?,?,?,?)", (hexid(n), hexid(4), hexid(3), n, 0, 0.9, "shift", "active", created, created))
    con.commit()
    con.close()

    alembic(db, "upgrade", "head")
    con = sqlite3.connect(db)
    rows = dict(con.execute("select id, added_on from plan_items").fetchall())
    assert rows == {hexid(5): "2026-01-05", hexid(6): "2026-02-01"}
    nullable = {r[1]: r[3] for r in con.execute("pragma table_info(plan_items)")}
    assert nullable["added_on"] == 1  # NOT NULL after the backfill
    assert "plan_item_id" in {r[1] for r in con.execute("pragma table_info(events)")}
    indexes = {r[1] for r in con.execute("pragma index_list(events)")}
    assert {"ix_events_child_id_type_plan_item_id_local_date", "ix_events_child_id_type_local_date"} <= indexes
    con.close()

    alembic(db, "downgrade", PREVIOUS)
    con = sqlite3.connect(db)
    assert "added_on" not in {r[1] for r in con.execute("pragma table_info(plan_items)")}
    assert con.execute("select count(*) from plan_items").fetchone()[0] == 2  # data survives the round trip
    con.close()
    alembic(db, "upgrade", "head")
    alembic(db, "check")  # models and migrations agree
