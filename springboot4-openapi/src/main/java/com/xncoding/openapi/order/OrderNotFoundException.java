package com.xncoding.openapi.order;

/** 订单不存在。映射 404，声明进 @ApiResponse。 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("订单不存在: " + id);
    }
}
