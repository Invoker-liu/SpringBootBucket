package com.xncoding.openapi.order;

/** 单号重复。映射 409，声明进 @ApiResponse。 */
public class DuplicateOrderException extends RuntimeException {

    public DuplicateOrderException(String orderNo) {
        super("订单号已存在: " + orderNo);
    }
}
