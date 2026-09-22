package com.xncoding.multisource.routing;

import com.xncoding.multisource.context.DataSourceContextHolder;
import com.xncoding.multisource.exception.DataSourceRoutingException;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;

/**
 * 在运行时按 {@link DataSourceContextHolder} 里的标识挑一个真实数据源。
 *
 * <p>父类 {@code AbstractRoutingDataSource} 在 Spring Framework 7 里
 * <b>依然存在、没有标注废弃</b>，而且 Spring Boot 4 专门为它写了一个
 * {@code RoutingDataSourceHealthContributor}（在 {@code spring-boot-jdbc} 里，
 * 会把每个 target 分别展开成独立的健康条目）。
 * 换句话说，这不是社区玩法，是官方认可的一等用法。
 *
 * <p>几个参数必须显式设置，否则默认行为会掩盖 bug：
 * <ul>
 *   <li>{@code setTargetDataSources} —— key 用 {@code DataSourceKey} 枚举，
 *       和 {@link DataSourceContextHolder} 里存的是同一个类型，才对得上；
 *   <li>{@code setDefaultTargetDataSource} —— 没标 {@code @DataSource} 时走哪个库；
 *   <li>{@code setLenientFallback(false)} —— <b>这一条最要紧</b>。默认值是 {@code true}，
 *       意思是「标识取不到时悄悄回退到默认库」。于是在 {@code @Transactional} 方法里
 *       切换数据源、或者新线程里 ThreadLocal 丢了，代码都不会报错，
 *       只是安静地查了主库 —— 这是最难发现的一类 bug。
 *       关掉之后，取不到标识就直接抛异常，问题在第一次跑的时候就暴露。
 * </ul>
 */
public class DynamicDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return DataSourceContextHolder.peek();
    }

    /**
     * 把父类的 {@code IllegalStateException} 翻译成专用异常。
     *
     * <p>父类这个方法是 {@code protected} 且不是 {@code final}（Framework 7 里实测如此），
     * 所以可以覆写。父类抛的原始信息长这样：
     * <pre>
     *   Cannot determine target DataSource for lookup key [null]
     * </pre>
     * 内容是对的，但类型太泛。翻一层的意义在于，全局异常处理器可以放心地
     * 只接 {@link DataSourceRoutingException}，不必提心吊胆地接整个
     * {@code IllegalStateException}。
     */
    @Override
    protected DataSource determineTargetDataSource() {
        try {
            return super.determineTargetDataSource();
        } catch (IllegalStateException ex) {
            throw new DataSourceRoutingException(
                    "没有可用数据源：" + ex.getMessage()
                            + "。通常是没标 @DataSource，或者标注的标识没注册到 targetDataSources 里",
                    ex);
        }
    }
}
