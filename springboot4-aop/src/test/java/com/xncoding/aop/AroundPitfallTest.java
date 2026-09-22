package com.xncoding.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * @Around 的两个经典坑，用最小 Spring 上下文（AspectJProxyFactory）实测：
 * 1. catch 之后返回 null 不上抛：异常被吞，调用方拿到 null 还以为成功；
 * 2. 忘了调 proceed：方法体根本没执行，同样静默返回。
 */
class AroundPitfallTest {

    /** 被代理的业务：执行就抛异常，用计数器证明「方法体到底跑没跑」 */
    static class FragileService {
        int executed;

        String doWork() {
            executed++;
            throw new IllegalStateException("boom");
        }
    }

    /** 坑一：捕获后不重抛，返回 null。 */
    @Aspect
    static class SwallowAspect {
        @Around("execution(* com.xncoding.aop..*.doWork(..))")
        Object swallow(ProceedingJoinPoint pjp) {
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** 坑二：advice 里压根不调 proceed，直接返回兜底值。 */
    @Aspect
    static class SkipAspect {
        @Around("execution(* com.xncoding.aop..*.doWork(..))")
        Object skip(ProceedingJoinPoint pjp) {
            return "fallback";
        }
    }

    @Test
    void swallow_aspect_hides_exception_and_returns_null() {
        FragileService service = new FragileService();
        AspectJProxyFactory factory = new AspectJProxyFactory(service);
        factory.addAspect(new SwallowAspect());
        FragileService proxy = factory.getProxy();

        // 调用方拿不到异常，只有返回 null 一个线索；方法体其实执行过了
        assertThatCode(proxy::doWork).doesNotThrowAnyException();
        assertThat(proxy.doWork()).isNull();
        assertThat(service.executed).isEqualTo(2);
    }

    @Test
    void skip_aspect_never_runs_business_method() {
        FragileService service = new FragileService();
        AspectJProxyFactory factory = new AspectJProxyFactory(service);
        factory.addAspect(new SkipAspect());
        FragileService proxy = factory.getProxy();

        String result = proxy.doWork();

        assertThat(result).isEqualTo("fallback");
        assertThat(service.executed).isZero();
    }
}
