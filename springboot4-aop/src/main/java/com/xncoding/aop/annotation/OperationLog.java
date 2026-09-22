package com.xncoding.aop.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计标记。审计切面按 module / action 两个维度写审计表，
 * 方法本身的耗时与成败由切面在 proceed 前后记录，业务代码零侵入。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /** 业务模块，例如「订单」 */
    String module();

    /** 操作名，例如「创建订单」 */
    String action();
}
