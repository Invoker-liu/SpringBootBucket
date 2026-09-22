package com.xncoding.aop.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * 全局异常处理，响应统一走 RFC 9457 的 application/problem+json
 * （由 spring.mvc.problemdetails.enabled=true 打开）。
 * 切面里抛出的异常沿调用链原样穿透，到这里和业务异常一视同仁。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IdempotentRejectedException.class)
    public ProblemDetail handleIdempotentRejected(IdempotentRejectedException ex, HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, "重复请求", ex.getMessage(), req,
                "urn:problem-type:duplicate-request");
        pd.setProperty("windowSeconds", ex.getWindowSeconds());
        return pd;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "订单不存在", ex.getMessage(), req,
                "urn:problem-type:order-not-found");
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex, HttpServletRequest req) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "库存不足", ex.getMessage(), req,
                "urn:problem-type:insufficient-stock");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  HttpServletRequest req, String type) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(type));
        pd.setProperty("path", req.getRequestURI());
        return pd;
    }
}
