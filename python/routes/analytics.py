from fastapi import APIRouter, Depends, HTTPException, Query, status
from typing import Optional
from datetime import datetime, timedelta
from bson import ObjectId

from database import orders_collection, products_collection
from security.jwt_handler import get_current_user

router = APIRouter(prefix="/api/analytics", tags=["analytics"])

TOP_PRODUCTS_LIMIT = 10


def _parse_date(value: str, field: str) -> datetime:
    for fmt in ("%Y-%m-%d", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M:%S.%f"):
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            continue
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).replace(tzinfo=None)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid {field} format. Use YYYY-MM-DD",
        )


def _period_key(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d")


@router.get("/sales")
async def get_sales_analytics(
    startDate: Optional[str] = Query(default=None),
    endDate: Optional[str] = Query(default=None),
    current_user: dict = Depends(get_current_user),
):
    date_filter: dict = {}
    if startDate:
        date_filter["$gte"] = _parse_date(startDate, "startDate")
    if endDate:
        end_dt = _parse_date(endDate, "endDate")
        # Treat endDate as inclusive (end-of-day) when given as a date.
        if end_dt.hour == 0 and end_dt.minute == 0 and end_dt.second == 0 and end_dt.microsecond == 0:
            end_dt = end_dt + timedelta(days=1) - timedelta(microseconds=1)
        date_filter["$lte"] = end_dt

    query: dict = {}
    if date_filter:
        query["createdAt"] = date_filter

    total_revenue = 0.0
    total_orders = 0
    product_totals: dict = {}
    revenue_by_day: dict = {}

    async for order in orders_collection.find(query):
        total_orders += 1
        order_total = float(order.get("total") or 0)
        total_revenue += order_total

        created_at = order.get("createdAt")
        if isinstance(created_at, datetime):
            key = _period_key(created_at)
            revenue_by_day[key] = revenue_by_day.get(key, 0.0) + order_total

        for item in order.get("items", []):
            pid = item.get("productId")
            qty = item.get("quantity") or 0
            if not pid:
                continue
            entry = product_totals.setdefault(pid, {"totalQuantity": 0, "totalRevenue": 0.0})
            entry["totalQuantity"] += qty

    # Resolve product names/prices for top product aggregates
    top_products = []
    if product_totals:
        valid_ids = [pid for pid in product_totals.keys() if ObjectId.is_valid(pid)]
        product_docs = {}
        if valid_ids:
            cursor = products_collection.find({"_id": {"$in": [ObjectId(pid) for pid in valid_ids]}})
            async for doc in cursor:
                product_docs[str(doc["_id"])] = doc

        for pid, totals in product_totals.items():
            doc = product_docs.get(pid)
            name = doc.get("name") if doc else None
            price = float(doc.get("price") or 0) if doc else 0.0
            totals["totalRevenue"] = round(price * totals["totalQuantity"], 2)
            top_products.append({
                "productId": pid,
                "name": name,
                "totalQuantity": totals["totalQuantity"],
                "totalRevenue": totals["totalRevenue"],
            })

        top_products.sort(key=lambda p: (p["totalQuantity"], p["totalRevenue"]), reverse=True)
        top_products = top_products[:TOP_PRODUCTS_LIMIT]

    revenue_by_period = [
        {"period": key, "revenue": round(value, 2)}
        for key, value in sorted(revenue_by_day.items())
    ]

    return {
        "totalRevenue": round(total_revenue, 2),
        "totalOrders": total_orders,
        "topProducts": top_products,
        "revenueByPeriod": revenue_by_period,
    }
