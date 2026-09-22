package com.xncoding.schedule.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务规则不满足：状态码由抛出方决定（409 冲突 / 400 参数不合法等）。
 */
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
