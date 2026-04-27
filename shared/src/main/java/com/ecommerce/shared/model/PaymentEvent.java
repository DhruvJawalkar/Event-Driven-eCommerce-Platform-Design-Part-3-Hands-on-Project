package com.ecommerce.shared.model;

import java.math.BigDecimal;

public record PaymentEvent(
        String messageId,
        String orderId,
        PaymentStatus status,
        BigDecimal amount,
        String paymentMethod,
        long timestamp
) {}
