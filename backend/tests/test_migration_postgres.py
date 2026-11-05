"""The real Alembic migrations on PostgreSQL (runs only when TEST_DATABASE_URL is set).

Uses a scratch database next to the test one (kidtube_test -> kidtube_mig_test), created and dropped here.
The scratch database gets a non-UTC timezone so a migration that depends on the server's session timezone fails.
"""

import os
import subprocess
import sys
from pathlib import Path

import pytest
from sqlalchemy import create_engine, make_url, text

BACKEND = Path(__file__).resolve().parent.parent
PREVIOUS = "5e8b9df77b8c"
BASE_URL = os.environ.get("TEST_DATABASE_URL")

pytestmark = pytest.mark.skipif(not BASE_URL, reason="needs TEST_DATABASE_URL (PostgreSQL)")


def alembic(url: str, *args: str) -> None:
    env = {**os.environ, "DATABASE_URL": url}
    r = subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, capture_output=True, text=True)
    assert r.returncode == 0, f"alembic {' '.join(args)} failed:\n{r.stderr}"


def uid(n: int) -> str:
    return f"00000000-0000-0000-0000-{n:012x}"


def test_upgrade_check_downgrade_and_data_preserving_backfill() -> None:
    admin_url = make_url(BASE_URL)
    scratch = admin_url.database.removesuffix("_test") + "_mig_test"
    assert scratch.endswith("_test") and scratch != admin_url.database
    scratch_url = admin_url.set(database=scratch).render_as_string(hide_password=False)
    admin = create_engine(admin_url, isolation_level="AUTOCOMMIT")
    with admin.connect() as c:
        c.execute(text(f'DROP DATABASE IF EXISTS "{scratch}"'))
        c.execute(text(f'CREATE DATABASE "{scratch}"'))
        c.execute(text(f"ALTER DATABASE \"{scratch}\" SET timezone = 'Asia/Karachi'"))
    try:
        alembic(scratch_url, "upgrade", "head")
        alembic(scratch_url, "check")  # models and migrations agree
        alembic(scratch_url, "downgrade", "base")
        alembic(scratch_url, "upgrade", PREVIOUS)

        eng = create_engine(scratch_url)
        ts_late, ts_edge = "2026-01-05 22:30:00+00", "2026-02-01 00:00:01.5+00"  # 22:30 UTC is already Jan 6 in Karachi
        with eng.begin() as c:
            c.execute(text(f"insert into families (id,name,timezone,created_at,updated_at) values ('{uid(1)}','F','Asia/Karachi','{ts_late}','{ts_late}')"))
            c.execute(text(f"insert into children (id,family_id,name,created_at,updated_at) values ('{uid(2)}','{uid(1)}','Ali','{ts_late}','{ts_late}')"))
            c.execute(text(
                "insert into videos (id,family_id,platform,platform_video_id,url,status,added_at,created_at,updated_at) "
                f"values ('{uid(3)}','{uid(1)}','youtube','abcdefghijk','u','verified','{ts_late}','{ts_late}','{ts_late}')"))
            c.execute(text(
                "insert into plans (id,child_id,pace_type,pace_value,active_weekdays,max_extra_per_day,start_date,version,created_at,updated_at) "
                f"values ('{uid(4)}','{uid(2)}','videos_per_day',1,127,1,'2026-01-01',1,'{ts_late}','{ts_late}')"))
            for n, ts in ((5, ts_late), (6, ts_edge)):
                c.execute(text(
                    "insert into plan_items (id,plan_id,video_id,position,required,watch_threshold,on_miss,status,created_at,updated_at) "
                    f"values ('{uid(n)}','{uid(4)}','{uid(3)}',{n},false,0.9,'shift','active','{ts}','{ts}')"))

        alembic(scratch_url, "upgrade", "head")
        with eng.connect() as c:
            rows = {str(i): str(d) for i, d in c.execute(text("select id, added_on from plan_items"))}
            nullable = c.execute(text(
                "select is_nullable from information_schema.columns where table_name='plan_items' and column_name='added_on'"
            )).scalar()
            indexes = {r[0] for r in c.execute(text("select indexname from pg_indexes where tablename='events'"))}
        assert rows == {uid(5): "2026-01-05", uid(6): "2026-02-01"}  # the UTC date, whatever the server timezone
        assert nullable == "NO"
        assert {"ix_events_child_id_type_plan_item_id_local_date", "ix_events_child_id_type_local_date"} <= indexes

        alembic(scratch_url, "downgrade", PREVIOUS)
        with eng.connect() as c:
            assert c.execute(text("select count(*) from plan_items")).scalar() == 2  # data survives the downgrade
        alembic(scratch_url, "upgrade", "head")
        alembic(scratch_url, "check")
        eng.dispose()
    finally:
        with admin.connect() as c:
            c.execute(text(f'DROP DATABASE IF EXISTS "{scratch}" WITH (FORCE)'))
        admin.dispose()
