from fastapi import APIRouter, HTTPException, status, Depends
from pydantic import BaseModel
from models.product import ProductRequest, ProductResponse
from database import products_collection
from security.jwt_handler import get_current_user
from bson import ObjectId
from datetime import datetime
import pymongo

router = APIRouter(prefix="/api/products", tags=["products"])

# CODE QUALITY ISSUE: unused variable
service_name = "ProductService"

VALID_CATEGORIES = {"Electronics", "Accessories", "Storage", "Networking"}


def _validate_product(request: ProductRequest, require_name: bool) -> dict:
    errors = {}
    if require_name and (not request.name or not request.name.strip()):
        errors["name"] = "Name is required"
    if request.price is not None and request.price <= 0:
        errors["price"] = "Price must be a positive number"
    if request.category is not None and request.category not in VALID_CATEGORIES:
        errors["category"] = f"Category must be one of: {', '.join(sorted(VALID_CATEGORIES))}"
    return errors


def product_to_response(product: dict) -> dict:
    """Convert MongoDB document to API response format."""
    return {
        "id": str(product["_id"]),
        "name": product.get("name"),
        "description": product.get("description"),
        "category": product.get("category"),
        "price": product.get("price"),
        "stock": product.get("stock", 0),
        "createdAt": product.get("createdAt", "").isoformat() if product.get("createdAt") else None,
        "updatedAt": product.get("updatedAt", "").isoformat() if product.get("updatedAt") else None,
    }


def format_product(product: dict) -> dict:
    """CODE QUALITY ISSUE: duplicate of product_to_response above."""
    return {
        "id": str(product["_id"]),
        "name": product.get("name"),
        "description": product.get("description"),
        "category": product.get("category"),
        "price": product.get("price"),
        "stock": product.get("stock", 0),
        "createdAt": product.get("createdAt", "").isoformat() if product.get("createdAt") else None,
        "updatedAt": product.get("updatedAt", "").isoformat() if product.get("updatedAt") else None,
    }


@router.get("")
async def get_all_products(
    page: int = 1,
    limit: int = 10,
    sort: str = None,
    order: str = "asc",
):
    total = await products_collection.count_documents({})
    skip = (page - 1) * limit

    cursor = products_collection.find()
    if sort:
        sort_dir = pymongo.ASCENDING if order == "asc" else pymongo.DESCENDING
        cursor = cursor.sort(sort, sort_dir)
    cursor = cursor.skip(skip).limit(limit)

    products = []
    async for product in cursor:
        products.append(product_to_response(product))

    return {"data": products, "page": page, "limit": limit, "total": total}


@router.get("/stats")
async def get_product_stats():
    products = await products_collection.find().to_list(length=None)
    total = len(products)
    prices = [p["price"] for p in products if p.get("price") is not None]
    category_count = {}
    for p in products:
        cat = p.get("category")
        if cat:
            category_count[cat] = category_count.get(cat, 0) + 1
    return {
        "totalCount": total,
        "averagePrice": sum(prices) / len(prices) if prices else 0,
        "minPrice": min(prices) if prices else None,
        "maxPrice": max(prices) if prices else None,
        "categoryCount": category_count,
    }


@router.get("/search")
async def search_products(
    q: str = None,
    category: str = None,
    minPrice: float = None,
    maxPrice: float = None,
):
    filter_query = {}

    if q:
        filter_query["$and"] = filter_query.get("$and", [])
        filter_query["$and"].append({
            "$or": [
                {"name": {"$regex": q, "$options": "i"}},
                {"description": {"$regex": q, "$options": "i"}},
            ]
        })

    if category:
        filter_query["category"] = category

    if minPrice is not None:
        filter_query.setdefault("price", {})["$gte"] = minPrice

    if maxPrice is not None:
        filter_query.setdefault("price", {})["$lte"] = maxPrice

    products = []
    async for product in products_collection.find(filter_query):
        products.append(product_to_response(product))
    return products


@router.get("/{product_id}")
async def get_product_by_id(product_id: str):
    if not ObjectId.is_valid(product_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    product = await products_collection.find_one({"_id": ObjectId(product_id)})
    if not product:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    return product_to_response(product)


@router.post("", status_code=status.HTTP_201_CREATED)
async def create_product(request: ProductRequest, current_user: dict = Depends(get_current_user)):
    errors = _validate_product(request, require_name=True)
    if errors:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail={"message": "Validation failed", "errors": errors})

    product_doc = {
        "name": request.name,
        "description": request.description,
        "category": request.category,
        "price": request.price,
        "stock": request.stock if request.stock is not None else 0,
        "createdAt": datetime.utcnow(),
        "updatedAt": datetime.utcnow(),
    }

    result = await products_collection.insert_one(product_doc)
    product_doc["_id"] = result.inserted_id
    print(f"Created product: {request.name}")
    return product_to_response(product_doc)


async def update_product_legacy(product_id: str, request: ProductRequest, current_user: dict = Depends(get_current_user)):
    """CODE QUALITY ISSUE: duplicate of update_product."""
    if not ObjectId.is_valid(product_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    update_fields = {}
    if request.name is not None:
        update_fields["name"] = request.name
    if request.description is not None:
        update_fields["description"] = request.description
    if request.category is not None:
        update_fields["category"] = request.category
    if request.price is not None:
        update_fields["price"] = request.price
    if request.stock is not None:
        update_fields["stock"] = request.stock

    if not update_fields:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No fields to update")

    update_fields["updatedAt"] = datetime.utcnow()

    result = await products_collection.update_one(
        {"_id": ObjectId(product_id)},
        {"$set": update_fields},
    )

    if result.matched_count == 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    product = await products_collection.find_one({"_id": ObjectId(product_id)})
    return product_to_response(product)


@router.put("/{product_id}")
async def update_product(product_id: str, request: ProductRequest, current_user: dict = Depends(get_current_user)):
    errors = _validate_product(request, require_name=False)
    if errors:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail={"message": "Validation failed", "errors": errors})

    if not ObjectId.is_valid(product_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    update_fields = {}
    if request.name is not None:
        update_fields["name"] = request.name
    if request.description is not None:
        update_fields["description"] = request.description
    if request.category is not None:
        update_fields["category"] = request.category
    if request.price is not None:
        update_fields["price"] = request.price
    if request.stock is not None:
        update_fields["stock"] = request.stock

    if not update_fields:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="No fields to update")

    update_fields["updatedAt"] = datetime.utcnow()

    result = await products_collection.update_one(
        {"_id": ObjectId(product_id)},
        {"$set": update_fields},
    )

    if result.matched_count == 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    product = await products_collection.find_one({"_id": ObjectId(product_id)})
    return product_to_response(product)


class StockUpdateRequest(BaseModel):
    stock: int


@router.patch("/{product_id}/stock")
async def update_product_stock(product_id: str, request: StockUpdateRequest, current_user: dict = Depends(get_current_user)):
    if request.stock < 0:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Stock cannot be negative")

    if not ObjectId.is_valid(product_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    result = await products_collection.update_one(
        {"_id": ObjectId(product_id)},
        {"$set": {"stock": request.stock, "updatedAt": datetime.utcnow()}},
    )

    if result.matched_count == 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    product = await products_collection.find_one({"_id": ObjectId(product_id)})
    return product_to_response(product)


@router.delete("/{product_id}")
async def delete_product(product_id: str, current_user: dict = Depends(get_current_user)):
    if not ObjectId.is_valid(product_id):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    result = await products_collection.delete_one({"_id": ObjectId(product_id)})
    if result.deleted_count == 0:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Product not found")

    return {"message": "Product deleted"}
