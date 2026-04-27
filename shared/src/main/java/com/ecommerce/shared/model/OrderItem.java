package com.ecommerce.shared.model;

import java.math.BigDecimal;

public record OrderItem(
        String sku,
        int qty,
        BigDecimal price
) {}
