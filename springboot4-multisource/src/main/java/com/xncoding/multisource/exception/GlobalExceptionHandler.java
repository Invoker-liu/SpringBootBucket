package com.xncoding.multisource.exception;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessException;
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
 *
 * <p>继承 {@link ResponseEntityExceptionHandler}，Spring MVC 内部那些
 * 405 / 415 / 400 已经被它归类好了，按需微调即可。
 *
 * <p>本篇特意把 {@link DataSourceRoutingException} 单独接出来，
 * 而不是笼统地接 {@code IllegalStateException}。理由在异常类注释里：
 * 路由失败和别的「状态不对」是两码事，混在一起会让排查方向跑偏。
 * 这也正是 {@code DynamicDataSource} 要覆写 {@code determineTargetDataSource()}
 * 做一层翻译的原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** 目标资源不存在 */
    private static final URI TYPE_RESOURCE_NOT_FOUND = URI.create("urn:problem-type:resource-not-found");

    /** 数据源路由失败 */
    private static final URI TYPE_DATASOURCE_ROUTING = URI.create("urn:problem-type:datasource-routing");

    /** 数据库访问失败 */
    private static final URI TYPE_DATA_ACCESS = URI.create("urn:problem-type:data-access");

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
     * 数据源路由失败：500。
     *
     * <p>这是服务端自己的配置问题，不是客户端把参数传错了，所以给 500 而不是 400。
     * {@code detail} 里保留原始提示（比如
     * {@code Cannot determine target DataSource for lookup key [REPORT]}），
     * 因为这条信息对定位问题几乎是决定性的，藏起来反而妨碍排查。
     */
    @ExceptionHandler(DataSourceRoutingException.class)
    public ResponseEntity<ProblemDetail> handleDataSourceRouting(DataSourceRoutingException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        problemDetail.setType(TYPE_DATASOURCE_ROUTING);
        problemDetail.setTitle("数据源路由失败");
        if (ex.getCause() != null) {
            problemDetail.setProperty("cause", ex.getCause().getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }

    /**
     * 数据访问异常：先看看是不是被包起来的路由失败。
     *
     * <p><b>这个方法是被一个失败的测试逼出来的。</b>
     * 路由失败发生在取连接的时候，而取连接发生在 mapper 调用内部 ——
     * MyBatis 的 {@code SqlSessionTemplate} 会把底层异常交给
     * {@code MyBatisExceptionTranslator} 翻一遍，翻出来的是
     * {@code MyBatisSystemException}，原始的 {@link DataSourceRoutingException}
     * 被塞进了 cause 链里。
     *
     * <p>结果就是上面那个处理方法<b>压根接不到</b>：类型对不上。
     * 请求最终落到通用兜底，返回一句「服务端内部错误」，
     * 那句最关键的「lookup key [REPORT] 找不到数据源」被吃掉了，排查时只能去翻日志。
     *
     * <p>教训是通用的：<b>按异常类型写 {@code @ExceptionHandler} 之前，
     * 先确认异常穿过多层框架之后类型还在不在。</b>
     * 中间件把异常重新包装是很常见的做法，沿着 cause 链找一遍比相信第一层类型可靠。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ProblemDetail> handleDataAccess(DataAccessException ex) {
        DataSourceRoutingException routing = findCause(ex, DataSourceRoutingException.class);
        if (routing != null) {
            return handleDataSourceRouting(routing);
        }
        logger.error("数据访问异常", ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "数据库访问失败，请稍后重试");
        problemDetail.setType(TYPE_DATA_ACCESS);
        problemDetail.setTitle("数据库访问失败");
        problemDetail.setProperty("cause", ex.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }

    /**
     * 兜底：未预期异常统一 500，不把堆栈透给客户端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        logger.error("未预期的服务端异常", ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "服务端内部错误，请稍后重试");
        problemDetail.setTitle("服务器内部错误");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problemDetail);
    }

    /**
     * 沿 cause 链找第一个指定类型的异常。
     *
     * <p>不用 {@code NestedRuntimeException.getMostSpecificCause()}：它一路走到链尾，
     * 而要找的那个往往在中间 —— 比如
     * {@code MyBatisSystemException → DataSourceRoutingException → IllegalStateException}，
     * 走到链尾拿到的是 {@code IllegalStateException}，正好是最不需要的那个。
     * 顺手挡一下自引用的 cause，避免写得不好的一层把自己套起来转不出来。
     */
    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    /**
     * {@code @RequestBody} 上的 Bean Validation 失败：逐字段原因放进 {@code errors}。
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
     * 方法级参数校验失败（如 {@code @RequestParam} 上的约束）：同样收集到 {@code errors}。
     *
     * <p>这个和上面那个必须<b>都</b>处理。Boot 4 里控制类上不加 {@code @Validated}
     * 时方法校验走的是内建机制，抛 {@code HandlerMethodValidationException}；
     * 一旦有人手贱加了 {@code @Validated}，就改走 AOP 抛 {@code ConstraintViolationException}，
     * 而那个不在 {@code ResponseEntityExceptionHandler} 的覆盖范围里，会变成 500。
     * 工程里没有加 {@code @Validated}，这里保留处理是为了以后加的时候不至于踩。
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
     * 请求体无法解析：给出可读提示而不是原始解析异常。
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
