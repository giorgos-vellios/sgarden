package com.sgarden.service;

import com.sgarden.dto.OrderRequest;
import com.sgarden.model.Order;
import com.sgarden.model.OrderItem;
import com.sgarden.model.Product;
import com.sgarden.repository.OrderRepository;
import com.sgarden.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class OrderService {

    public static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
            "pending", Set.of("confirmed", "cancelled"),
            "confirmed", Set.of("shipped"),
            "shipped", Set.of("delivered"),
            "delivered", Set.of(),
            "cancelled", Set.of()
    );

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    public static class OrderValidationException extends RuntimeException {
        public OrderValidationException(String msg) {
            super(msg);
        }
    }

    public List<Order> getAllOrders(String status) {
        if (status != null && !status.isBlank()) {
            return orderRepository.findByStatus(status);
        }
        return orderRepository.findAll();
    }

    public Optional<Order> getOrderById(String id) {
        return orderRepository.findById(id);
    }

    public Order createOrder(OrderRequest request) {
        List<OrderItem> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw new OrderValidationException("Order must contain at least one item");
        }

        // Validate stock and compute total in two passes so we can fail atomically before any write.
        Map<String, Product> productCache = new HashMap<>();
        double total = 0.0;
        for (OrderItem item : items) {
            Product product = loadProduct(item, productCache);
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            if (qty <= 0) {
                throw new OrderValidationException("Quantity must be positive");
            }
            int stock = product.getStock() != null ? product.getStock() : 0;
            if (stock < qty) {
                throw new OrderValidationException("Insufficient stock for product: " + product.getName());
            }
            double price = product.getPrice() != null ? product.getPrice() : 0.0;
            total += price * qty;
        }

        // All validated — now deduct stock.
        for (OrderItem item : items) {
            Product product = productCache.get(item.getProductId());
            product.setStock(product.getStock() - item.getQuantity());
            productRepository.save(product);
        }

        Order order = new Order();
        order.setItems(new ArrayList<>(items));
        order.setTotal(round2(total));
        order.setStatus("pending");
        return orderRepository.save(order);
    }

    public Optional<Order> updateOrder(String id, OrderRequest request) {
        Optional<Order> existingOpt = orderRepository.findById(id);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }
        Order existing = existingOpt.get();

        List<OrderItem> newItems = request.getItems();
        if (newItems == null || newItems.isEmpty()) {
            throw new OrderValidationException("Order must contain at least one item");
        }

        // Restore stock from the previous items first.
        if (existing.getItems() != null) {
            for (OrderItem oldItem : existing.getItems()) {
                productRepository.findById(oldItem.getProductId()).ifPresent(p -> {
                    int restored = (p.getStock() != null ? p.getStock() : 0) + oldItem.getQuantity();
                    p.setStock(restored);
                    productRepository.save(p);
                });
            }
        }

        // Validate the new items.
        Map<String, Product> productCache = new HashMap<>();
        double total = 0.0;
        for (OrderItem item : newItems) {
            Product product = loadProduct(item, productCache);
            int qty = item.getQuantity() != null ? item.getQuantity() : 0;
            if (qty <= 0) {
                throw new OrderValidationException("Quantity must be positive");
            }
            int stock = product.getStock() != null ? product.getStock() : 0;
            if (stock < qty) {
                throw new OrderValidationException("Insufficient stock for product: " + product.getName());
            }
            double price = product.getPrice() != null ? product.getPrice() : 0.0;
            total += price * qty;
        }

        for (OrderItem item : newItems) {
            Product product = productCache.get(item.getProductId());
            product.setStock(product.getStock() - item.getQuantity());
            productRepository.save(product);
        }

        existing.setItems(new ArrayList<>(newItems));
        existing.setTotal(round2(total));
        return Optional.of(orderRepository.save(existing));
    }

    public Optional<Order> updateStatus(String id, String newStatus) {
        Optional<Order> orderOpt = orderRepository.findById(id);
        if (orderOpt.isEmpty()) {
            return Optional.empty();
        }
        Order order = orderOpt.get();
        String current = order.getStatus() != null ? order.getStatus() : "pending";

        Set<String> allowed = VALID_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new OrderValidationException("Invalid transition from '" + current + "' to '" + newStatus + "'");
        }
        order.setStatus(newStatus);
        return Optional.of(orderRepository.save(order));
    }

    public boolean deleteOrder(String id) {
        if (orderRepository.existsById(id)) {
            orderRepository.deleteById(id);
            return true;
        }
        return false;
    }

    private Product loadProduct(OrderItem item, Map<String, Product> cache) {
        String pid = item.getProductId();
        if (pid == null || pid.isBlank()) {
            throw new OrderValidationException("productId is required");
        }
        Product cached = cache.get(pid);
        if (cached != null) {
            return cached;
        }
        Optional<Product> opt = productRepository.findById(pid);
        if (opt.isEmpty()) {
            throw new OrderValidationException("Product not found: " + pid);
        }
        cache.put(pid, opt.get());
        return opt.get();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
