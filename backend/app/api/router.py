from fastapi import APIRouter

from app.api import auth, children, devices, events, feed, health, plans, videos

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(auth.router)
api_router.include_router(children.router)
api_router.include_router(devices.router)
api_router.include_router(videos.router)
api_router.include_router(plans.router)
api_router.include_router(events.router)
api_router.include_router(feed.router)
