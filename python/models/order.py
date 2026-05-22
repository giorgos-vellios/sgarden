from pydantic import BaseModel
from typing import Optional, List
from datetime import datetime


class OrderItem(BaseModel):
    productId: str
    quantity: int


class OrderRequest(BaseModel):
    items: List[OrderItem]


class OrderStatusRequest(BaseModel):
    status: str


class OrderItemResponse(BaseModel):
    productId: str
    quantity: int


class OrderResponse(BaseModel):
    id: str
    items: List[OrderItemResponse]
    total: float
    status: Optional[str] = "pending"
    createdAt: Optional[str] = None
    updatedAt: Optional[str] = None
