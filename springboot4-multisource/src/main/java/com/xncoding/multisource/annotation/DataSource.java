package com.xncoding.multisource.annotation;

import com.xncoding.multisource.context.DataSourceKey;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明这个方法（或这个类里的所有方法）要走哪个库。
 *
 * <p>方法上的注解优先于类上的。由 {@code routing.DataSourceAspect} 在方法执行前
 * 写入 {@code DataSourceContextHolder}，执行完再弹回去。
 *
 * <p><b>它和 {@code @Transactional} 的相对顺序是有讲究的</b>，
 * 拿捏不对就会出现「注解明明标了却还是查的主库」。原因和验证方式见
 * {@code routing.DataSourceAspect} 的类注释。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataSource {

    /** 目标数据源。不给默认值，逼着调用方写清楚。 */
    DataSourceKey value();
}
