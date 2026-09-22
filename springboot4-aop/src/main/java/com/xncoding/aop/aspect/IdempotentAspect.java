package com.xncoding.aop.aspect;

import com.xncoding.aop.annotation.Idempotent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等防重切面，@Order(1) 站在整条切面链的最外层：
 * 重复请求在进入计费、审计之前就被打回，不会留下脏审计数据。
 */
@Aspect
@Component
@Order(1)
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    /** 请求指纹 -> 过期时间戳。单机演示够用，分布式场景要换成 Redis 这类共享存储 */
    private final ConcurrentHashMap<String, Long> tokens = new ConcurrentHashMap<>();

    @Around("@annotation(idempotent)")
    public Object guard(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        // 指纹 = 短方法签名 + 入参。DTO 用 record，toString 逐字段输出，同内容请求指纹相同
        String key = pjp.getSignature().toShortString() + ":" + Arrays.deepToString(pjp.getArgs());
        long now = System.currentTimeMillis();
        long deadline = now + idempotent.windowSeconds() * 1000L;
        // 先清过期项再放行，避免长跑内存膨胀
        tokens.entrySet().removeIf(e -> e.getValue() <= now);
        if (tokens.putIfAbsent(key, deadline) != null) {
            log.warn("AOP_IDEM reject key={} window={}s", key, idempotent.windowSeconds());
            throw new com.xncoding.aop.exception.IdempotentRejectedException(idempotent.windowSeconds());
        }
        log.info("AOP_IDEM enter key={}", key);
        try {
            return pjp.proceed();
        } finally {
            log.info("AOP_IDEM exit key={}", key);
        }
    }
}
