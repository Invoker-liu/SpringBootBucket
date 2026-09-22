package com.xncoding.testing.config;

import com.xncoding.testing.order.DuplicateOrderException;
import com.xncoding.testing.order.OrderNotFoundException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(java.net.URI.create("urn:problem-type:order-not-found"));
        return pd;
    }

    @ExceptionHandler(DuplicateOrderException.class)
    ProblemDetail handleDuplicate(DuplicateOrderException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(java.net.URI.create("urn:problem-type:duplicate-order"));
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(java.net.URI.create("urn:problem-type:illegal-order-state"));
        return pd;
    }
}
