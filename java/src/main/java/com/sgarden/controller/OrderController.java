package com.sgarden.controller;

import com.sgarden.dto.ErrorResponse;
import com.sgarden.dto.OrderRequest;
import com.sgarden.dto.OrderStatusRequest;
import com.sgarden.model.Order;
import com.sgarden.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(orderService.getAllOrders(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getOrderById(@PathVariable String id) {
        return orderService.getOrderById(id)
                .map(order -> ResponseEntity.ok((Object) order))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse("Order not found")));
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody OrderRequest request) {
        try {
            Order order = orderService.createOrder(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(order);
        } catch (OrderService.OrderValidationException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateOrder(@PathVariable String id, @RequestBody OrderRequest request) {
        try {
            return orderService.updateOrder(id, request)
                    .map(order -> ResponseEntity.ok((Object) order))
                    .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ErrorResponse("Order not found")));
        } catch (OrderService.OrderValidationException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(ex.getMessage()));
        }
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateOrderStatus(@PathVariable String id, @RequestBody OrderStatusRequest body) {
        if (body == null || body.getStatus() == null || body.getStatus().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("status is required"));
        }
        try {
            return orderService.updateStatus(id, body.getStatus())
                    .map(order -> ResponseEntity.ok((Object) order))
                    .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ErrorResponse("Order not found")));
        } catch (OrderService.OrderValidationException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteOrder(@PathVariable String id) {
        if (orderService.deleteOrder(id)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Order not found"));
    }
}
