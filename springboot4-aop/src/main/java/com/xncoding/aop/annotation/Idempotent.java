package com.xncoding.aop.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等防重标记。切面按「方法签名 + 入参」生成请求指纹，
 * 窗口内第二次带同样指纹的请求直接打回 409。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** 防重窗口秒数：同一指纹在这个窗口内只放行一次 */
    int windowSeconds() default 60;
}
