package com.xncoding.security.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderNo) {
        super("订单不存在: " + orderNo);
    }
}
