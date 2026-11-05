# KidTube backend

```
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env
pytest
uvicorn app.main:app --reload
```

## Tests

Default: in-memory SQLite.

```
.venv/bin/pytest
```

Against PostgreSQL (the same suite, plus the Postgres-only migration test):

```
TEST_DATABASE_URL=postgresql+psycopg://kidtube_test:test-only-pw@localhost:5432/kidtube_test .venv/bin/pytest
```

The fixtures create the schema once per session and `TRUNCATE` every table before each test, so the database is wiped.
For that reason pytest refuses to start unless the database name in `TEST_DATABASE_URL` ends with `_test`.
The user needs `CREATEDB`: `tests/test_migration_postgres.py` creates and drops a scratch database (`kidtube_mig_test`).
