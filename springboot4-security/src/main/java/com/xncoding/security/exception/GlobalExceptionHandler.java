package com.xncoding.security.exception;

import com.xncoding.security.order.DuplicateOrderException;
import com.xncoding.security.order.OrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;

/** 业务异常渲染成 RFC 9457 的 problem+json（spring.mvc.problemdetails.enabled=true 时由 MVC 渲染）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleNotFound(OrderNotFoundException ex, WebRequest req) {
        return problem(HttpStatus.NOT_FOUND, "订单不存在", ex.getMessage(), req,
                "urn:problem-type:order-not-found");
    }

    @ExceptionHandler(DuplicateOrderException.class)
    public ProblemDetail handleDuplicate(DuplicateOrderException ex, WebRequest req) {
        return problem(HttpStatus.CONFLICT, "订单号已存在", ex.getMessage(), req,
                "urn:problem-type:duplicate-order");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, WebRequest req, String type) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(type));
        pd.setTitle(title);
        pd.setProperty("path", ((ServletWebRequest) req).getRequest().getRequestURI());
        return pd;
    }
}
