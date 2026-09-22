package com.xncoding.batch.exception;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 全局异常处理，错误响应统一为 RFC 9457 的 {@code application/problem+json}。
 * <p>
 * 骨架与前几篇一致（继承 {@link ResponseEntityExceptionHandler}，
 * 同时处理 {@link MethodArgumentNotValidException} 与
 * {@link HandlerMethodValidationException} 两类校验异常——Controller 类上
 * 不能加 {@code @Validated}，否则方法校验走 AOP 代理抛
 * {@code ConstraintViolationException}，返回 500 而不是 400）。
 * <p>
 * 本篇的异常来源三类——校验失败（400/404）、业务状态冲突（409，
 * 如同一文件重复对账的幂等拒绝）、以及任务触发的意外失败（500）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 目标资源不存在 */
    private static final URI TYPE_RESOURCE_NOT_FOUND = URI.create("urn:problem-type:resource-not-found");

    /** 业务规则不满足 */
    private static final URI TYPE_BUSINESS_RULE_VIOLATION =
            URI.create("urn:problem-type:business-rule-violation");

    /**
     * 资源不存在：404。典型场景：查询不存在的订单号。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFound(ResourceNotFoundException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problemDetail.setType(TYPE_RESOURCE_NOT_FOUND);
        problemDetail.setTitle("资源不存在");
        problemDetail.setProperty("resourceType", ex.getResourceType());
        problemDetail.setProperty("resourceId", ex.getResourceId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problemDetail);
    }

    /**
     * 业务规则冲突：状态码由异常自带。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setType(TYPE_BUSINESS_RULE_VIOLATION);
        problemDetail.setTitle("业务规则不满足");
        return ResponseEntity.status(ex.getStatus()).body(problemDetail);
    }

    /**
     * 兜底：未预期异常统一返回 500，避免把堆栈信息暴露给客户端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        logger.error("未预期的服务端异常", ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "服务端内部错误，请稍后重试");
        problemDetail.setTitle("服务器内部错误");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }

    /**
     * {@code @RequestBody} 上的 Bean Validation 失败：把逐字段错误放进 {@code errors} 属性。
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problemDetail = ex.getBody();
        problemDetail.setTitle("请求体校验失败");
        problemDetail.setDetail("请求体字段校验未通过，逐字段原因见 errors");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", String.valueOf(fieldError.getDefaultMessage())))
                .toList();
        problemDetail.setProperty("errors", errors);
        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    /**
     * 方法级参数校验失败（如 {@code @RequestParam} 上的规则）：同样收集到 {@code errors}。
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                           HttpHeaders headers,
                                                                           HttpStatusCode status,
                                                                           WebRequest request) {
        ProblemDetail problemDetail = ex.getBody();
        problemDetail.setTitle("请求参数校验失败");
        problemDetail.setDetail("查询参数校验未通过，逐字段原因见 errors");
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .map(this::toErrorEntry)
                .toList();
        problemDetail.setProperty("errors", errors);
        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    /**
     * 请求体无法解析（JSON 语法错误、类型不匹配等）：给出可读提示而不是原始解析异常。
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status,
                "请求体不是合法的 JSON，或字段类型与接口定义不匹配");
        problemDetail.setTitle("请求体格式错误");
        problemDetail.setProperty("cause", ex.getMostSpecificCause().getMessage());
        return handleExceptionInternal(ex, problemDetail, headers, status, request);
    }

    private Map<String, String> toErrorEntry(ParameterValidationResult result) {
        String parameter = result.getMethodParameter().getParameterName();
        String message = result.getResolvableErrors().stream()
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse("参数不合法");
        return Map.of(
                "field", parameter == null ? "unknown" : parameter,
                "message", message);
    }
}
