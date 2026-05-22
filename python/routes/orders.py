from fastapi import APIRouter, HTTPException, status, Depends, Query
from typing import Optional
from models.order import OrderRequest, OrderStatusRequest
from database import orders_collection, products_collection
from security.jwt_handler import get_current_user
from bson import ObjectId
from datetime import datetime

VALID_TRANSITIONS = {
    "pending": {"confirmed", "cancelled"},
    "confirmed": {"shipped"},
    "shipped": {"delivered"},
    "delivered": set(),
    "cancelled": set(),
}

router = APIRouter(prefix="/api/orders", tags=["orders"])


def order_to_response(order: dict) -> dict:
    return {
        "id": str(order["_id"]),
        "items": order.get("items", []),
        "total": order.get("total", 0),
        "status": order.get("status", "pending"),
        "createdAt": order["createdAt"].isoformat() if order.get("createdAt") else None,
        "updatedAt": order["updatedAt"].isoformat() if order.get("updatedAt") else None,
    }


async def _calculate_total_and_validate_stock(items: list, check_stock: bool = True) -> float:
    """Fetch product prices, validate stock availability, and return total."""
    if not items:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Order must contain at least one item")

    total = 0.0
    for item in items:
        product_id = item.productId if hasattr(item, "productId") else item["productId"]
        quantity = item.quantity if hasattr(item, "quantity") else item["quantity"]

        if not ObjectId.is_valid(product_id):
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Invalid productId: {product_id}")

        product = await products_collection.find_one({"_id": ObjectId(product_id)})
        if not product:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=f"Product not found: {product_id}")

        if check_stock and product.get("stock", 0) < quantity:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Insufficient stock for product: {product.get('name', product_id)}",
            )

        price = product.get("price") or 0
        total += price * quantity

    return round(total, 2)


@router.get("")
async def get_all_orders(
    status: Optional[str] = Query(default=None),
    current_user: dict = Depends(get_current_user),
):
    query = {}
    if status is not None:
        query["status"] = status
    orders = []
    async for order in orders_collection.find(query):
        orders.append(order_to_response(order))
    return orders


@router.get("/{order_id}")
async def get_order_by_id(order_id: str, current_user: dict = Depends(get_current_user)):
    if not ObjectId.is_valid(order_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    order = await orders_collection.find_one({"_id": ObjectId(order_id)})
    if not order:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    return order_to_response(order)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_order(request: OrderRequest, current_user: dict = Depends(get_current_user)):
    if not request.items:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Order must contain at least one item")

    total = await _calculate_total_and_validate_stock(request.items, check_stock=True)

    # Reduce stock for each product (all-or-nothing, already validated above)
    for item in request.items:
        await products_collection.update_one(
            {"_id": ObjectId(item.productId)},
            {"$inc": {"stock": -item.quantity}, "$set": {"updatedAt": datetime.utcnow()}},
        )

    items_doc = [{"productId": item.productId, "quantity": item.quantity} for item in request.items]
    order_doc = {
        "items": items_doc,
        "total": total,
        "status": "pending",
        "createdAt": datetime.utcnow(),
        "updatedAt": datetime.utcnow(),
    }

    result = await orders_collection.insert_one(order_doc)
    order_doc["_id"] = result.inserted_id
    return order_to_response(order_doc)


@router.patch("/{order_id}/status")
async def update_order_status(
    order_id: str,
    body: OrderStatusRequest,
    current_user: dict = Depends(get_current_user),
):
    if not ObjectId.is_valid(order_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    order = await orders_collection.find_one({"_id": ObjectId(order_id)})
    if not order:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    current_status = order.get("status", "pending")
    new_status = body.status

    allowed = VALID_TRANSITIONS.get(current_status, set())
    if new_status not in allowed:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid transition from '{current_status}' to '{new_status}'",
        )

    await orders_collection.update_one(
        {"_id": ObjectId(order_id)},
        {"$set": {"status": new_status, "updatedAt": datetime.utcnow()}},
    )
    updated = await orders_collection.find_one({"_id": ObjectId(order_id)})
    return order_to_response(updated)


@router.put("/{order_id}")
async def update_order(order_id: str, request: OrderRequest, current_user: dict = Depends(get_current_user)):
    if not ObjectId.is_valid(order_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    existing = await orders_collection.find_one({"_id": ObjectId(order_id)})
    if not existing:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    if not request.items:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Order must contain at least one item")

    # Restore stock from old items before re-validating new items
    for old_item in existing.get("items", []):
        await products_collection.update_one(
            {"_id": ObjectId(old_item["productId"])},
            {"$inc": {"stock": old_item["quantity"]}, "$set": {"updatedAt": datetime.utcnow()}},
        )

    total = await _calculate_total_and_validate_stock(request.items, check_stock=True)

    # Deduct stock for new items
    for item in request.items:
        await products_collection.update_one(
            {"_id": ObjectId(item.productId)},
            {"$inc": {"stock": -item.quantity}, "$set": {"updatedAt": datetime.utcnow()}},
        )

    items_doc = [{"productId": item.productId, "quantity": item.quantity} for item in request.items]
    await orders_collection.update_one(
        {"_id": ObjectId(order_id)},
        {"$set": {"items": items_doc, "total": total, "updatedAt": datetime.utcnow()}},
    )

    updated = await orders_collection.find_one({"_id": ObjectId(order_id)})
    return order_to_response(updated)


@router.delete("/{order_id}")
async def delete_order(order_id: str, current_user: dict = Depends(get_current_user)):
    if not ObjectId.is_valid(order_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    result = await orders_collection.delete_one({"_id": ObjectId(order_id)})
    if result.deleted_count == 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Order not found")

    return {"message": "Order deleted"}
