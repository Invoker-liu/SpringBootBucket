package com.xncoding.mybatis.exception;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * 继承 {@link ResponseEntityExceptionHandler} 而不是从零写 {@code @ExceptionHandler(Exception.class)}：
 * Spring MVC 内部抛出的 405、415、400 等异常都已被它归类处理，继承后按需微调即可。
 * <p>
 * 相比上一篇多了一个 {@link DuplicateKeyException} 的处理——换成真实数据库之后，
 * 唯一索引冲突会以 Spring 的 {@code DataAccessException} 形式冒出来，
 * 不拦的话就是 500，而它本质上是 409。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 目标资源不存在 */
    private static final URI TYPE_RESOURCE_NOT_FOUND = URI.create("urn:problem-type:resource-not-found");

    /** 业务规则不满足 */
    private static final URI TYPE_BUSINESS_RULE_VIOLATION =
            URI.create("urn:problem-type:business-rule-violation");

    /** 唯一键冲突 */
    private static final URI TYPE_DUPLICATE_KEY = URI.create("urn:problem-type:duplicate-key");

    /**
     * 资源不存在：404。
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
     * 业务规则冲突：状态码由异常自带（409 / 422）。
     * <p>
     * title 统一用中文短描述，具体差异交给 HTTP 状态码与 {@code detail} 表达。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problemDetail.setType(TYPE_BUSINESS_RULE_VIOLATION);
        problemDetail.setTitle("业务规则不满足");
        return ResponseEntity.status(ex.getStatus()).body(problemDetail);
    }

    /**
     * 唯一索引冲突：409。
     * <p>
     * 数据库抛出来的错不该直接透给客户端，这里翻译成与业务冲突一致的语义。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateKey(DuplicateKeyException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "数据已存在，违反唯一约束");
        problemDetail.setType(TYPE_DUPLICATE_KEY);
        problemDetail.setTitle("唯一约束冲突");
        problemDetail.setProperty("cause", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
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
     * 方法级参数校验失败（如 {@code @RequestParam} 上的 {@code @Min}）：同样收集到 {@code errors}。
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
     * 请求体无法解析（JSON 语法错误、枚举值非法、类型不匹配等）：给出可读提示而不是原始解析异常。
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
