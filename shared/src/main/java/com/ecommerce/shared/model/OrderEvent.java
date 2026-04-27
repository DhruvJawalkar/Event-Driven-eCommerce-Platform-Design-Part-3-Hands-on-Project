package com.ecommerce.shared.model;

import java.math.BigDecimal;
import java.util.List;

public record OrderEvent(
        String messageId,
        EventType eventType,
        String orderId,
        String customerId,
        List<OrderItem> items,
        BigDecimal totalAmount,
        String currency,
        long timestamp
) {}
