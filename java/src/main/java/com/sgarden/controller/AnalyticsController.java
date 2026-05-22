package com.sgarden.controller;

import com.sgarden.dto.ErrorResponse;
import com.sgarden.model.Order;
import com.sgarden.model.OrderItem;
import com.sgarden.model.Product;
import com.sgarden.repository.OrderRepository;
import com.sgarden.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final int TOP_PRODUCTS_LIMIT = 10;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public AnalyticsController(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    private Instant parseStart(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("Invalid startDate format. Use YYYY-MM-DD");
    }

    private Instant parseEnd(String value) {
        if (value == null) return null;
        try {
            // End-of-day inclusive for date-only values
            LocalDate ld = LocalDate.parse(value);
            return ld.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("Invalid endDate format. Use YYYY-MM-DD");
    }

    @GetMapping("/sales")
    public ResponseEntity<?> getSalesAnalytics(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        Instant start;
        Instant end;
        try {
            start = parseStart(startDate);
            end = parseEnd(endDate);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(ex.getMessage()));
        }

        List<Order> orders = orderRepository.findAll();

        double totalRevenue = 0.0;
        int totalOrders = 0;
        Map<String, long[]> productQuantities = new HashMap<>(); // productId -> [totalQuantity]
        Map<String, Double> revenueByDay = new TreeMap<>();

        for (Order order : orders) {
            Instant createdAt = order.getCreatedAt();
            if (start != null && (createdAt == null || createdAt.isBefore(start))) continue;
            if (end != null && (createdAt == null || createdAt.isAfter(end))) continue;

            totalOrders++;
            double orderTotal = order.getTotal() != null ? order.getTotal() : 0.0;
            totalRevenue += orderTotal;

            if (createdAt != null) {
                String key = LocalDate.ofInstant(createdAt, ZoneId.of("UTC")).toString();
                revenueByDay.merge(key, orderTotal, Double::sum);
            }

            if (order.getItems() != null) {
                for (OrderItem item : order.getItems()) {
                    String pid = item.getProductId();
                    int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                    if (pid == null) continue;
                    productQuantities.computeIfAbsent(pid, k -> new long[]{0})[0] += qty;
                }
            }
        }

        // Resolve product names and revenue
        List<Map<String, Object>> topProducts = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : productQuantities.entrySet()) {
            String pid = entry.getKey();
            long qty = entry.getValue()[0];
            Optional<Product> productOpt = productRepository.findById(pid);
            String name = productOpt.map(Product::getName).orElse(null);
            double price = productOpt.map(p -> p.getPrice() != null ? p.getPrice() : 0.0).orElse(0.0);
            double revenue = Math.round(price * qty * 100.0) / 100.0;

            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("productId", pid);
            entryMap.put("name", name);
            entryMap.put("totalQuantity", qty);
            entryMap.put("totalRevenue", revenue);
            topProducts.add(entryMap);
        }
        topProducts.sort(Comparator
                .<Map<String, Object>, Long>comparing(m -> (Long) m.get("totalQuantity"))
                .thenComparing(m -> (Double) m.get("totalRevenue"))
                .reversed());
        if (topProducts.size() > TOP_PRODUCTS_LIMIT) {
            topProducts = topProducts.subList(0, TOP_PRODUCTS_LIMIT);
        }

        List<Map<String, Object>> revenueByPeriod = new ArrayList<>();
        for (Map.Entry<String, Double> e : revenueByDay.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("period", e.getKey());
            entry.put("revenue", Math.round(e.getValue() * 100.0) / 100.0);
            revenueByPeriod.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalRevenue", Math.round(totalRevenue * 100.0) / 100.0);
        response.put("totalOrders", totalOrders);
        response.put("topProducts", topProducts);
        response.put("revenueByPeriod", revenueByPeriod);
        return ResponseEntity.ok(response);
    }
}
