package com.sgarden.dto;

import com.sgarden.model.Product;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PagedProductsResponse {
    private List<Product> data;
    private int page;
    private int limit;
    private long total;
}
