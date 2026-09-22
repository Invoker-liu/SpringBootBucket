package com.xncoding.oauth2.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateOrderException extends RuntimeException {

    public DuplicateOrderException(String orderNo) {
        super("订单号已存在: " + orderNo);
    }
}
