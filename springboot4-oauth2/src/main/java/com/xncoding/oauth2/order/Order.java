package com.xncoding.oauth2.order;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(String orderNo, BigDecimal amount, String status,
                    String createdBy, Instant createdAt) {

    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
