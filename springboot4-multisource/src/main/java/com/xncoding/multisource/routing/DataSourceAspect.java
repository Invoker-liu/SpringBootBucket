package com.xncoding.multisource.routing;

import com.xncoding.multisource.annotation.DataSource;
import com.xncoding.multisource.context.DataSourceContextHolder;
import com.xncoding.multisource.context.DataSourceKey;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 把 {@link DataSource} 注解翻译成一次数据源切换。
 *
 * <h2>为什么 {@code @Order(0)} 不是随手写的</h2>
 *
 * <p>切数据源和开事务是两条互不知情的切面，谁先执行决定了结果对不对。
 *
 * <p>Spring 的事务最终是拿到一个 {@code Connection} 之后把它绑定到当前线程。
 * 所以「挑哪个库」这件事必须在<b>取连接之前</b>做完。顺序反了会出现这种情况：
 * 事务先开，连接从默认库（交易库）借出来并绑定；切面再改标识；
 * 之后的 SQL 走的还是那条已经绑好的连接 —— <b>注解看起来生效了，实际没生效</b>。
 *
 * <p>Spring 的 advice 是按 order 从小到大往外包的，order 越小越靠外、越先执行。
 * Boot 4 里 {@code @EnableTransactionManagement} 的 advisor 用的是默认值
 * {@code Ordered.LOWEST_PRECEDENCE}（{@code Integer.MAX_VALUE}），
 * 不过 {@code TransactionAutoConfiguration} 没有暴露任何调整它的属性 ——
 * 所以只能从我们这头压下去，给一个足够小的值。
 *
 * <p>写 {@code 0} 而不是 {@code Ordered.HIGHEST_PRECEDENCE}，是给别的切面留位置：
 * 以后要是有日志切面、幂等切面想包在数据源切换外面，还能用负数插进去。
 * 具体生效顺序在启动日志里能核对，见 README 的「怎么确认切面顺序」一节。
 *
 * <h2>为什么用栈而不是直接 set</h2>
 *
 * <p>用 {@code push}/{@code poll} 配成一对，是为了支持嵌套调用：
 * 外层 {@code @DataSource(BIZ)} 调内层 {@code @DataSource(POS)}，
 * 内层跑完弹掉之后外层还是 BIZ。如果只 set 不还原，
 * 内层那个 POS 会一直粘在线程上，外层剩下的代码就跑到错库去了。
 * 弹回放在 {@code finally} 里，异常路径也要还原。
 */
@Aspect
@Component
@Order(DataSourceAspect.ORDER)
public class DataSourceAspect {

    /** 必须小于事务 advisor 的顺序，理由见类注释。 */
    public static final int ORDER = 0;

    private static final Logger log = LoggerFactory.getLogger(DataSourceAspect.class);

    @Around("@annotation(com.xncoding.multisource.annotation.DataSource) "
            + "|| @within(com.xncoding.multisource.annotation.DataSource)")
    public Object switchDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        DataSourceKey key = resolveKey(joinPoint);
        DataSourceContextHolder.push(key);
        if (log.isDebugEnabled()) {
            log.debug("切到数据源 [{}]，目标方法 {}",
                    key, joinPoint.getSignature().toShortString());
        }
        try {
            return joinPoint.proceed();
        } finally {
            DataSourceContextHolder.poll();
        }
    }

    /**
     * 取生效的标识：方法上的注解优先于类上的。
     *
     * <p>这里不能用 {@code joinPoint.getSignature().getDeclaringType()} 去拿注解 ——
     * 那是<b>接口</b>或者代理声明的方法，类上的 {@code @DataSource} 有可能取不到。
     * 要沿目标类的真实方法找。
     */
    private DataSourceKey resolveKey(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        DataSource onMethod = method.getAnnotation(DataSource.class);
        if (onMethod != null) {
            return onMethod.value();
        }

        Class<?> targetClass = joinPoint.getTarget().getClass();
        DataSource onClass = targetClass.getAnnotation(DataSource.class);
        if (onClass != null) {
            return onClass.value();
        }

        // 走到这里说明切点表达式匹配上了但注解取不到，属于框架层面的异常，不该发生
        throw new IllegalStateException(
                "切点匹配到了 @DataSource 但注解取不到：" + targetClass.getName()
                        + "#" + method.getName());
    }
}
