package com.xncoding.restful.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务规则冲突，映射为对应的 4xx 状态码。
 * <p>
 * 携带 {@link HttpStatus} 而非让异常处理器按类型猜状态码：
 * "订单号已存在"是 409 Conflict，"状态不允许流转"是 422 Unprocessable Content，
 * 二者都是业务异常，但语义与客户端处理方式不同。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** 唯一键冲突，HTTP 409 */
    public static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }

    /** 语义上无法处理，HTTP 422 */
    public static BusinessException unprocessable(String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
