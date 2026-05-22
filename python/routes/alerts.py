from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from database import products_collection
from security.jwt_handler import get_current_user

router = APIRouter(prefix="/api/alerts", tags=["alerts"])

_threshold = 10


class ThresholdRequest(BaseModel):
    threshold: int


def _severity(stock: int, threshold: int) -> str:
    if stock == 0 or stock < threshold * 0.25:
        return "critical"
    if stock < threshold * 0.5:
        return "warning"
    return "info"


@router.get("")
async def get_alerts(current_user: dict = Depends(get_current_user)):
    alerts = []
    async for product in products_collection.find({"stock": {"$lt": _threshold}}):
        stock = product.get("stock", 0)
        alerts.append({
            "productName": product.get("name"),
            "currentStock": stock,
            "severity": _severity(stock, _threshold),
        })
    return alerts


@router.put("/threshold")
async def set_threshold(body: ThresholdRequest, current_user: dict = Depends(get_current_user)):
    global _threshold
    if body.threshold < 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Threshold must be non-negative")
    _threshold = body.threshold
    return {"threshold": _threshold}
