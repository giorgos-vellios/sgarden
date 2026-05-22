package com.sgarden.dto;

import com.sgarden.model.OrderItem;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {
    private List<OrderItem> items;
}
