package com.xncoding.async.exception;

import org.springframework.http.HttpStatus;

/** 业务规则不满足时抛出，由全局异常处理翻成 problem+json。 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
