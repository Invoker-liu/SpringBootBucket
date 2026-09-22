package com.xncoding.jackson3.web;

import com.xncoding.jackson3.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.DatabindException;

/**
 * 统一错误出口。Jackson 3 的 DatabindException 是非受检异常（RuntimeException 子类），
 * 方法签名不需要 throws，这里按 400 problem+json 输出。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DatabindException.class)
    public ProblemDetail handleDatabind(DatabindException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "请求体解析失败: " + ex.getOriginalMessage());
        pd.setTitle("Invalid request body");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleNotFound(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }
}
