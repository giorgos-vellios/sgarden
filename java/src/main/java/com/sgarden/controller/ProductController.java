package com.sgarden.controller;

import com.sgarden.dto.ErrorResponse;
import com.sgarden.dto.PagedProductsResponse;
import com.sgarden.dto.ProductRequest;
import com.sgarden.dto.ProductStatsResponse;
import com.sgarden.dto.StockUpdateRequest;
import com.sgarden.model.Product;
import com.sgarden.repository.ProductRepository;
import com.sgarden.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ProductRepository productRepository;

    public ProductController(ProductService productService, ProductRepository productRepository) {
        this.productService = productService;
        this.productRepository = productRepository;
    }

    @GetMapping
    public ResponseEntity<PagedProductsResponse> getAllProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "asc") String order) {
        return ResponseEntity.ok(productService.getPagedProducts(page, limit, sort, order));
    }

    @GetMapping("/stats")
    public ResponseEntity<ProductStatsResponse> getProductStats() {
        return ResponseEntity.ok(productService.getProductStats());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Product>> searchProducts(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double minPrice,
            @RequestParam(required = false) Double maxPrice) {
        return ResponseEntity.ok(productService.searchProducts(q, category, minPrice, maxPrice));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProductById(@PathVariable String id) {
        return productService.getProductById(id)
                .map(product -> ResponseEntity.ok((Object) product))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Product not found")));
    }

    @PostMapping
    public ResponseEntity<?> createProduct(@RequestBody ProductRequest request) {
        Product product = productService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(product);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProduct(@PathVariable String id, @RequestBody ProductRequest request) {
        return productService.updateProduct(id, request)
                .map(product -> ResponseEntity.ok((Object) product))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Product not found")));
    }

    @PatchMapping("/{id}/stock")
    public ResponseEntity<?> updateStock(@PathVariable String id, @RequestBody StockUpdateRequest body) {
        return productService.updateStock(id, body.getStock())
                .map(product -> ResponseEntity.ok((Object) product))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Product not found")));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable String id) {
        if (productService.deleteProduct(id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Product not found"));
    }

    @GetMapping("/summary/{productId}")
    public ResponseEntity<?> getProductSummary(@PathVariable String productId) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Product not found"));
        }
        Product product = productOpt.get();
        Map<String, Object> response = new HashMap<>();
        response.put("id", product.getId());
        response.put("name", product.getName());
        response.put("description", product.getDescription());
        response.put("category", product.getCategory());
        response.put("price", product.getPrice());
        response.put("stock", product.getStock());
        response.put("createdAt", product.getCreatedAt());
        response.put("updatedAt", product.getUpdatedAt());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/card/{productId}")
    public ResponseEntity<?> getProductCard(@PathVariable String productId) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Product not found"));
        }
        Product product = productOpt.get();
        Map<String, Object> response = new HashMap<>();
        response.put("id", product.getId());
        response.put("name", product.getName());
        response.put("description", product.getDescription());
        response.put("category", product.getCategory());
        response.put("price", product.getPrice());
        response.put("stock", product.getStock());
        response.put("createdAt", product.getCreatedAt());
        response.put("updatedAt", product.getUpdatedAt());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{productId}/discount")
    public ResponseEntity<?> applyDiscount(@PathVariable String productId,
                                           @RequestBody Map<String, Double> body) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Product not found"));
        }
        Double discountPercent = body.get("discountPercent");
        if (discountPercent == null || discountPercent < 0 || discountPercent > 100) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("discountPercent must be between 0 and 100"));
        }
        Product product = productOpt.get();
        double discounted = product.getPrice() * (1 - discountPercent / 100);
        product.setPrice(Math.round(discounted * 100.0) / 100.0);
        productRepository.save(product);
        return ResponseEntity.ok(Map.of("message", "Discount applied", "newPrice", product.getPrice()));
    }

    @PostMapping("/{productId}/restock")
    public ResponseEntity<?> applyRestock(@PathVariable String productId,
                                          @RequestBody Map<String, Integer> body) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Product not found"));
        }
        Integer quantity = body.get("quantity");
        if (quantity == null || quantity <= 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("quantity must be greater than zero"));
        }
        Product product = productOpt.get();
        product.setStock(product.getStock() + quantity);
        productRepository.save(product);
        return ResponseEntity.ok(Map.of("message", "Restock applied", "newStock", product.getStock()));
    }
}
