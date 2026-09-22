package com.xncoding.security.order;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单。管理后台演示用，存内存即可；鉴权才是本篇的主题。 */
public record Order(String orderNo,
                    BigDecimal amount,
                    String status,
                    String createdBy,
                    Instant createdAt) {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
