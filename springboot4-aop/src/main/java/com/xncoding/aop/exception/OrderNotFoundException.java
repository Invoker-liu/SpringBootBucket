package com.xncoding.aop.exception;

/** 订单不存在。切面链上的 @AfterThrowing 与审计 ERROR 行都会因它触发。 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderNo) {
        super("订单不存在：" + orderNo);
    }
}
