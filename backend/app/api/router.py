from fastapi import APIRouter

from app.api import auth, children, devices, health, videos

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(auth.router)
api_router.include_router(children.router)
api_router.include_router(devices.router)
api_router.include_router(videos.router)
