package com.xncoding.openapi.config;

import java.net.URI;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.xncoding.openapi.order.DuplicateOrderException;
import com.xncoding.openapi.order.OrderNotFoundException;

/**
 * 全局 problem+json 出口：业务异常（404/409）与校验失败（400）的响应结构，
 * 与 OpenApiConfig 里声明的复用响应组件对齐。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setType(URI.create("urn:problem-type:order-not-found"));
        pd.setTitle("订单不存在");
        return pd;
    }

    @ExceptionHandler(DuplicateOrderException.class)
    ProblemDetail duplicate(DuplicateOrderException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setType(URI.create("urn:problem-type:duplicate-order"));
        pd.setTitle("单号重复");
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败");
        pd.setType(URI.create("urn:problem-type:bad-request"));
        pd.setTitle("请求参数校验失败");
        pd.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "" : fe.getDefaultMessage()))
                .toList());
        return pd;
    }
}
