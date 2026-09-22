package com.xncoding.aop.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 业务链路跟踪切面，@Order(3)。四种 advice 各干一件事：
 * @Before 进场记参数，@AfterReturning 记返回值，@AfterThrowing 记异常，
 * @After 兜底记录退出（成功失败都走）。
 * 切点用 execution：命中 service 包下全部方法，服务层不需要贴任何注解。
 */
@Aspect
@Component
@Order(3)
public class TraceAspect {

    private static final Logger log = LoggerFactory.getLogger(TraceAspect.class);

    @Pointcut("execution(* com.xncoding.aop.service..*.*(..))")
    public void serviceLayer() {
        // 切点标记方法，逻辑写在 advice 里
    }

    @Before("serviceLayer()")
    public void enter(JoinPoint jp) {
        log.info("AOP_TRACE enter {} args={}", jp.getSignature().toShortString(), Arrays.toString(jp.getArgs()));
    }

    @AfterReturning(pointcut = "serviceLayer()", returning = "result")
    public void onReturn(JoinPoint jp, Object result) {
        log.info("AOP_TRACE return {} result={}", jp.getSignature().toShortString(), result);
    }

    @AfterThrowing(pointcut = "serviceLayer()", throwing = "ex")
    public void onThrow(JoinPoint jp, Throwable ex) {
        log.info("AOP_TRACE throws {} ex={}", jp.getSignature().toShortString(), ex.getClass().getSimpleName());
    }

    @After("serviceLayer()")
    public void exit(JoinPoint jp) {
        log.info("AOP_TRACE exit {}", jp.getSignature().toShortString());
    }

}
