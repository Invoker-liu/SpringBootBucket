package com.xncoding.testing.order;

public class DuplicateOrderException extends RuntimeException {

    public DuplicateOrderException(String orderNo) {
        super("订单号已存在: " + orderNo);
    }
}
