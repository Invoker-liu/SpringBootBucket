package com.xncoding.multisource.config;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * 方案 A：静态多数据源之一 —— 交易库。
 *
 * <h2>为什么必须手工写这么多</h2>
 *
 * <p>注册第二个 {@code DataSource} 之后，Boot 4 会一口气放弃一串自动配置，
 * 它们的类级条件里都有 {@code @ConditionalOnSingleCandidate(DataSource)}。
 * 那条条件的意思是「容器里只有一个候选，或者有多个但其中一个标了 {@code @Primary}」。
 * 两个平级的数据源让条件不成立，于是下面这些 bean 全都不会出现：
 *
 * <ul>
 *   <li>{@code JdbcTemplate} / {@code NamedParameterJdbcTemplate} / {@code JdbcClient}
 *       （{@code JdbcTemplateAutoConfiguration} 与它的 {@code @Import}）
 *   <li>{@code PlatformTransactionManager}（{@code DataSourceTransactionManagerAutoConfiguration}）
 *   <li>{@code schema.sql} / {@code data.sql} 的自动执行（{@code DataSourceInitializationAutoConfiguration}）
 *   <li><b>MyBatis-Plus 的整条自动配置</b>（{@code MybatisPlusAutoConfiguration}）
 * </ul>
 *
 * <p>注意这些退让都是<b>静默</b>的：不报错、不警告，只是 bean 没了。
 * 尤其是最后一条 —— mapper 扫描一起消失，报错会指向「找不到 XxxMapper」，
 * 很容易往 mapper 包名、编译输出目录那个方向去查，其实根因在这。
 *
 * <h2>数据源属性为什么拆成两段前缀</h2>
 *
 * <p>{@code app.datasource.pos.*} 绑到 {@link DataSourceProperties}（连接信息），
 * {@code app.datasource.pos.hikari.*} 绑到 {@link HikariDataSource}（连接池参数）。
 * 这和单数据源时 {@code spring.datasource.*} / {@code spring.datasource.hikari.*}
 * 的分工是一样的，Boot 官方给多数据源的推荐写法。
 *
 * <p>顺带一提 {@link DataSourceProperties} 可以直接复用，不用自己写属性类 ——
 * 它自带 {@code initializeDataSourceBuilder()} 这个方法，
 * 里面已经处理好了驱动推断、url 里的用户名密码覆盖等一堆细节。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.xncoding.multisource.mapper.pos",
        sqlSessionFactoryRef = "posSqlSessionFactory")
public class PosDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.pos")
    public DataSourceProperties posDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.pos.hikari")
    public HikariDataSource posDataSource(DataSourceProperties posDataSourceProperties) {
        return posDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    /**
     * 建表和灌种子数据。
     *
     * <p>这里的写法就是「{@code spring.datasource.schema} 已经不能用了」之后的替代品。
     * <b>不能</b>指望 {@code spring.sql.init.*}：它挂在
     * {@code DataSourceInitializationAutoConfiguration} 上，
     * 那个类同样有 {@code @ConditionalOnSingleCandidate(DataSource)}，多数据源下不生效。
     * 而且它也没法告诉 Spring「这段脚本发给哪个库」。
     *
     * <p>{@link DataSourceInitializer} 是 spring-jdbc 里现成的
     * {@code InitializingBean}，Framework 7 里没有废弃，用它最省事。
     */
    @Bean
    public DataSourceInitializer posDataSourceInitializer(
            @Qualifier("posDataSource") DataSource posDataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(posDataSource);
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(
                new ClassPathResource("sql/pos-schema.sql"),
                new ClassPathResource("sql/pos-data.sql")));
        return initializer;
    }

    /**
     * 交易库的 MyBatis 工厂。
     *
     * <p>方法返回类型写成 {@code MybatisSqlSessionFactoryBean} 是有意的 ——
     * 它实现了 {@code FactoryBean<SqlSessionFactory>}，所以 Spring 容器里
     * <b>名为 {@code posSqlSessionFactory} 的 bean 是它生产出来的那个
     * {@code SqlSessionFactory}</b>，而不是工厂本身。
     * {@code @MapperScan(sqlSessionFactoryRef = "posSqlSessionFactory")} 要的就是这个名字。
     */
    @Bean
    public MybatisSqlSessionFactoryBean posSqlSessionFactory(
            @Qualifier("posDataSource") DataSource posDataSource) throws IOException {
        return MybatisFactorySupport.factory(posDataSource, "classpath*:/mapper/pos/*.xml");
    }

    /**
     * 交易库专属的事务管理器。
     *
     * <p>用 {@link JdbcTransactionManager} 而不是它的父类
     * {@code DataSourceTransactionManager}。差别在提交阶段：
     * 父类在 {@code commit()} 时如果数据库报错，抛的是原生
     * {@code SQLException}；子类会翻成 Spring 的
     * {@code DataAccessException}。Boot 4 的自动配置用的就是子类
     * （{@code DataSourceTransactionManagerAutoConfiguration$JdbcTransactionManagerConfiguration}），
     * 手工配置保持一致。
     *
     * <p><b>bean 名字很重要，后面 {@code @Transactional("posTransactionManager")} 要按名字引用。</b>
     * 三个事务管理器谁都没标 {@code @Primary}，所以写 {@code @Transactional} 不指定名字会直接报
     * {@code NoUniqueBeanDefinitionException}。这是故意的 —— 多数据源下
     * 「默认用哪个库的事务」本身就是个应该被显式回答的问题。
     */
    @Bean
    public JdbcTransactionManager posTransactionManager(
            @Qualifier("posDataSource") DataSource posDataSource) {
        return new JdbcTransactionManager(posDataSource);
    }
}
