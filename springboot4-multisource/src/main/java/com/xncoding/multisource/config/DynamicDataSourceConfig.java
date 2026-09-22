package com.xncoding.multisource.config;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.xncoding.multisource.context.DataSourceKey;
import com.xncoding.multisource.routing.DynamicDataSource;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 方案 B：动态路由数据源。
 *
 * <h2>bean 的数量对比</h2>
 *
 * <p>方案 A 是「一个库一套」，两个库就是两个 {@code DataSource}、
 * 两个 {@code SqlSessionFactory}、两个事务管理器。
 * 方案 B 只多出一个路由数据源，其余全是<b>一套</b> ——
 * 因为 MyBatis 眼里的「那个数据源」始终只有路由数据源一个，
 * 库的切换发生在更下面一层。
 *
 * <h2>它为什么没能让 MyBatis-Plus 的自动配置复活</h2>
 *
 * <p>直觉上「多了一个 {@code @Primary} 之外的东西」，
 * 但注意路由数据源本身也是 {@code DataSource}，容器里现在有<b>三个</b>
 * {@code DataSource}（pos、biz、路由），依然没有哪一个标了 {@code @Primary}，
 * {@code @ConditionalOnSingleCandidate(DataSource)} 照样不成立，
 * 所以这里的工厂还是得手工声明。
 *
 * <p>反过来说，如果这个项目<b>只走动态方案</b>、把 pos / biz 藏成路由数据源的内部
 * 目标而不暴露成 bean，那么给路由数据源标上 {@code @Primary} 之后，
 * 容器里就只剩一个候选，MyBatis-Plus 的自动配置会<b>重新生效</b>，
 * 上面的工厂声明可以整段删掉。
 * 这个「一个 {@code @Primary} 就能让一串自动配置复活」的行为，
 * 工程里有一个独立测试专门验证，见 {@code PrimaryAutoConfigurationProbeTest}。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.xncoding.multisource.mapper.probe",
        sqlSessionFactoryRef = "dynamicSqlSessionFactory")
public class DynamicDataSourceConfig {

    /**
     * 路由数据源。
     *
     * <p>方法参数用具体类型 + {@code @Qualifier} 而不是直接写 {@code DataSource} ——
     * 容器里已经有两个 {@code DataSource} 了，按类型注入会直接歧义报错。
     */
    @Bean
    public DynamicDataSource dynamicDataSource(
            @Qualifier("posDataSource") DataSource posDataSource,
            @Qualifier("bizDataSource") DataSource bizDataSource) {
        Map<Object, Object> targets = new LinkedHashMap<>();
        targets.put(DataSourceKey.POS, posDataSource);
        targets.put(DataSourceKey.BIZ, bizDataSource);
        // 故意不注册 DataSourceKey.REPORT，用来跑「标了一个没配的库」这条路径

        DynamicDataSource routingDataSource = new DynamicDataSource();
        routingDataSource.setTargetDataSources(targets);
        routingDataSource.setDefaultTargetDataSource(posDataSource);
        // 关掉宽容回退。默认是 true，含义是「key 在 map 里找不到就悄悄用默认库」。
        // 关掉之后，一个【非 null 但没注册】的 key 会让它直接抛异常，
        // 而不是安静地跑到交易库去。
        //
        // 但要说清楚它【管不到】什么：当前 key 为 null 时，
        // 父类会无条件回退到默认数据源，跟这个开关没关系。
        // 源码里那个判断是
        //     if (dataSource == null && (this.lenientFallback || lookupKey == null))
        // 后半截 `lookupKey == null` 是独立的一条路。
        // 所以「新线程里 ThreadLocal 丢了」这种事故，把 lenientFallback 设成 false 也拦不住。
        routingDataSource.setLenientFallback(false);
        return routingDataSource;
    }

    @Bean
    public MybatisSqlSessionFactoryBean dynamicSqlSessionFactory(DynamicDataSource dynamicDataSource)
            throws IOException {
        return MybatisFactorySupport.factory(dynamicDataSource, "classpath*:/mapper/probe/*.xml");
    }

    /**
     * 动态方案下只需要一个事务管理器，绑在路由数据源上。
     *
     * <p>它开事务时先问路由数据源要连接，路由数据源再去问当前线程的标识，
     * 于是一个事务天然只落在一个库上 —— 这正是动态方案的边界：
     * <b>它能切换库，但没法在一个事务里同时改两个库</b>。
     */
    @Bean
    public JdbcTransactionManager dynamicTransactionManager(DynamicDataSource dynamicDataSource) {
        return new JdbcTransactionManager(dynamicDataSource);
    }
}
