package com.xncoding.aop.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口耗时统计切面，@Order(2) 包住后面所有切面：
 * 统计出来的耗时包含 Trace / Audit 两层开销，接近 HTTP 层的真实耗时。
 * 切点用 @within：命中所有标注 @RestController 的类里的方法。
 */
@Aspect
@Component
@Order(2)
public class MetricsAspect {

    private static final Logger log = LoggerFactory.getLogger(MetricsAspect.class);

    @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
    public void webLayer() {
        // 切点标记方法，逻辑写在 advice 里
    }

    @Around("webLayer()")
    public Object timeRequest(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        String target = pjp.getSignature().toShortString();
        log.info("AOP_METRICS enter {}", target);
        try {
            Object result = pjp.proceed();
            log.info("AOP_TIME {} costMs={} status=OK", target, elapsedMs(t0));
            return result;
        } catch (Throwable t) {
            // 计时切面只记录不拦截：异常原样上抛，交给全局异常处理器渲染
            log.info("AOP_TIME {} costMs={} status=ERROR({})", target, elapsedMs(t0), t.getClass().getSimpleName());
            throw t;
        } finally {
            log.info("AOP_METRICS exit {}", target);
        }
    }

    private long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
