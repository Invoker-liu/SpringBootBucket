package com.xncoding.aop.aspect;

import com.xncoding.aop.annotation.OperationLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 操作审计切面，@Order(4) 站在切面链最里层：
 * 统计的 cost_ms 是业务方法自身的耗时，不含外层各切面的开销；
 * 成败两个分支都在 finally 里落库，失败行带异常类名。
 * 切点用 @annotation：只有标注 @OperationLog 的方法才写审计。
 */
@Aspect
@Component
@Order(4)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final JdbcTemplate jdbc;

    public AuditAspect(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Around("@annotation(operationLog)")
    public Object audit(ProceedingJoinPoint pjp, OperationLog operationLog) throws Throwable {
        long t0 = System.nanoTime();
        String method = pjp.getSignature().toShortString();
        String args = summarize(pjp.getArgs());
        String status = "OK";
        String error = null;
        log.info("AOP_AUDIT enter {} {}", operationLog.module(), operationLog.action());
        try {
            return pjp.proceed();
        } catch (Throwable e) {
            status = "ERROR";
            error = e.getClass().getSimpleName();
            throw e;
        } finally {
            long costMs = (System.nanoTime() - t0) / 1_000_000;
            // finally 落库：业务抛异常也要留下审计行，error 列记异常类名
            jdbc.update("""
                            INSERT INTO t_audit_log(module, action, method, args, status, error, cost_ms, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, NOW(3))
                            """,
                    operationLog.module(), operationLog.action(), method, args, status, error, (int) costMs);
            log.info("AOP_AUDIT done {} {} status={} costMs={}",
                    operationLog.module(), operationLog.action(), status, costMs);
        }
    }

    private String summarize(Object[] args) {
        String joined = Arrays.stream(args)
                .map(a -> a == null ? "null" : a.toString())
                .collect(Collectors.joining(", "));
        return joined.length() > 200 ? joined.substring(0, 200) : joined;
    }
}
