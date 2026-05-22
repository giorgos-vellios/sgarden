package com.sgarden.controller;

import com.sgarden.dto.AlertResponse;
import com.sgarden.dto.ErrorResponse;
import com.sgarden.dto.ThresholdRequest;
import com.sgarden.model.Product;
import com.sgarden.repository.ProductRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final ProductRepository productRepository;
    private int threshold = 10;

    public AlertController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    private String severity(int stock, int threshold) {
        if (threshold <= 0) {
            return "info";
        }
        if (stock == 0 || stock < threshold * 0.25) {
            return "critical";
        }
        if (stock < threshold * 0.5) {
            return "warning";
        }
        return "info";
    }

    @GetMapping
    public ResponseEntity<List<AlertResponse>> getAlerts() {
        List<AlertResponse> alerts = new ArrayList<>();
        for (Product product : productRepository.findAll()) {
            int stock = product.getStock() != null ? product.getStock() : 0;
            if (stock < threshold) {
                alerts.add(new AlertResponse(product.getName(), stock, severity(stock, threshold)));
            }
        }
        return ResponseEntity.ok(alerts);
    }

    @PutMapping("/threshold")
    public ResponseEntity<?> setThreshold(@RequestBody ThresholdRequest body) {
        if (body == null || body.getThreshold() == null || body.getThreshold() < 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("Threshold must be non-negative"));
        }
        threshold = body.getThreshold();
        return ResponseEntity.ok(Map.of("threshold", threshold));
    }
}
