package com.xncoding.transaction.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;

/**
 * 全局异常处理，响应统一走 RFC 9457 的 {@code application/problem+json}
 * （由 {@code spring.mvc.problemdetails.enabled=true} 打开）。
 *
 * <p>这里除了常规的参数校验，重点处理三个事务异常，它们是三个不同层面的「事务出问题了」：
 * <ul>
 *   <li>{@link IllegalTransactionStateException} —— 传播行为本身就不允许这么调
 *       （比如 REQUIRED 里调 NEVER），属于「调用姿势错了」。</li>
 *   <li>{@link CannotCreateTransactionException} —— 连数据库都连不上，事务根本开不起来。</li>
 *   <li>{@link UnexpectedRollbackException} —— 最阴的一个：外层什么都没做错，
 *       提交的时候才被告知「你的事务早就被标记成只能回滚了」。它的成因在内层，
 *       报错却在外层。</li>
 * </ul>
 *
 * <p>注意 {@code ProblemDetail} 的字段是按字母序输出的，正文里的响应体示例要照实际输出写，
 * 不要按「status 在第一个」的直觉写。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyInvalid(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .reduce((a, b) -> a + "；" + b)
                .orElse("请求体校验失败");
        return problem(HttpStatus.BAD_REQUEST, "请求参数不合法", detail, req, "urn:problem-type:validation");
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodInvalid(HandlerMethodValidationException ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "请求参数不合法",
                "方法参数校验失败：" + ex.getReason(), req, "urn:problem-type:validation");
    }

    @ExceptionHandler(BalanceNotEnoughException.class)
    public ProblemDetail handleBalanceNotEnough(BalanceNotEnoughException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "余额不足", ex.getMessage(), req, "urn:problem-type:balance-not-enough");
    }

    @ExceptionHandler(TransferRejectedException.class)
    public ProblemDetail handleTransferRejected(TransferRejectedException ex, HttpServletRequest req) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "转账被拒", ex.getMessage(), req,
                "urn:problem-type:transfer-rejected");
    }

    @ExceptionHandler(IllegalTransactionStateException.class)
    public ProblemDetail handleIllegalState(IllegalTransactionStateException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "事务状态不允许这次调用", ex.getMessage(), req,
                "urn:problem-type:illegal-transaction-state");
    }

    @ExceptionHandler(CannotCreateTransactionException.class)
    public ProblemDetail handleCannotCreate(CannotCreateTransactionException ex, HttpServletRequest req) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "事务无法开启",
                ex.getMessage(), req, "urn:problem-type:cannot-create-transaction");
    }

    @ExceptionHandler(UnexpectedRollbackException.class)
    public ProblemDetail handleUnexpectedRollback(UnexpectedRollbackException ex, HttpServletRequest req) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "提交时才发现事务已被标记回滚",
                ex.getMessage(), req, "urn:problem-type:unexpected-rollback");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  HttpServletRequest req, String type) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(type));
        pd.setInstance(URI.create(req.getRequestURI()));
        return pd;
    }
}
