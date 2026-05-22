package com.sgarden.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AlertResponse {
    private String productName;
    private Integer currentStock;
    private String severity;
}
