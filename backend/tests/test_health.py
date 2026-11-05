from fastapi.testclient import TestClient


def test_health(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def _docs_paths(settings) -> list[int]:
    from app.main import create_app

    client = TestClient(create_app(settings))
    return [client.get(p).status_code for p in ("/docs", "/redoc", "/openapi.json")]


def test_api_docs_are_on_in_dev_and_off_otherwise() -> None:
    from app.core.config import Settings

    secret = "test-secret-" + "x" * 32
    assert _docs_paths(Settings(_env_file=None, app_env="dev", jwt_secret=secret)) == [200, 200, 200]
    assert _docs_paths(Settings(_env_file=None, app_env="prod", jwt_secret=secret)) == [404, 404, 404]
    # explicit override either way
    assert _docs_paths(Settings(_env_file=None, app_env="prod", jwt_secret=secret, enable_docs=True)) == [200, 200, 200]
    assert _docs_paths(Settings(_env_file=None, app_env="dev", jwt_secret=secret, enable_docs=False)) == [404, 404, 404]


def test_real_routes_still_work_in_prod() -> None:
    from app.core.config import Settings
    from app.main import create_app

    prod = TestClient(create_app(Settings(_env_file=None, app_env="prod", jwt_secret="test-secret-" + "x" * 32)))
    assert prod.get("/health").status_code == 200
    assert prod.get("/children").status_code == 401
