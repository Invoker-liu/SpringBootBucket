package com.xncoding.oauth2.order;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class OrderApiExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "urn:problem-type:order-not-found", "订单不存在", e.getMessage());
    }

    @ExceptionHandler(DuplicateOrderException.class)
    ProblemDetail conflict(DuplicateOrderException e) {
        return problem(HttpStatus.CONFLICT, "urn:problem-type:order-duplicate", "订单号已存在", e.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(java.net.URI.create(type));
        pd.setTitle(title);
        return pd;
    }
}
