package com.xncoding.multisource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把「多数据源会让自动配置全线退让」这件事变成可以跑的断言，而不是文章里的说法。
 *
 * <p>这些用例全部是「某个 bean <b>不存在</b>」的断言，看起来有点怪，
 * 但它们正好对应读者会遇到的那几个异常：
 * <ul>
 *   <li>{@code NoSuchBeanDefinitionException: JdbcTemplate} →
 *       {@link #jdbcTemplateAutoConfigurationBacksOff()}
 *   <li>{@code NoSuchBeanDefinitionException: PlatformTransactionManager} →
 *       {@link #transactionManagerAutoConfigurationBacksOff()}
 *   <li>{@code NoSuchBeanDefinitionException: XxxMapper}（报错方向最坑的一个）→
 *       {@link #mybatisPlusAutoConfigurationBacksOff()}
 * </ul>
 *
 * <p>对照实验在 {@link PrimaryAutoConfigurationProbeTest}：
 * 同样两个数据源，只要其中一个标上 {@code @Primary}，这一组断言就会全部反转。
 */
@SpringBootTest
@ActiveProfiles("test")
class AutoConfigurationBackoffTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("容器里有三个数据源，而且没有一个标了 @Primary")
    void threeDataSourcesNonePrimary() {
        assertThat(context.getBeanNamesForType(DataSource.class))
                .containsExactlyInAnyOrder("posDataSource", "bizDataSource", "dynamicDataSource");
    }

    @Test
    @DisplayName("JdbcTemplate、NamedParameterJdbcTemplate、JdbcClient 一个都没被创建")
    void jdbcTemplateAutoConfigurationBacksOff() {
        // 这三个的类级条件都有 @ConditionalOnSingleCandidate(DataSource)，
        // 而上一条用例已经证明：三个 DataSource 谁也没标 @Primary，条件不成立
        assertThat(context.getBeanNamesForType(JdbcTemplate.class)).isEmpty();
        assertThat(context.getBeanNamesForType(NamedParameterJdbcTemplate.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JdbcClient.class)).isEmpty();
    }

    @Test
    @DisplayName("事务管理器只剩手工声明的那三个")
    void transactionManagerAutoConfigurationBacksOff() {
        assertThat(context.getBeanNamesForType(PlatformTransactionManager.class))
                .containsExactlyInAnyOrder("posTransactionManager",
                        "bizTransactionManager",
                        "dynamicTransactionManager");
    }

    @Test
    @DisplayName("SqlSessionFactory 也全是手工的，MyBatis-Plus 自动配置整体没生效")
    void mybatisPlusAutoConfigurationBacksOff() {
        assertThat(context.getBeanNamesForType(SqlSessionFactory.class))
                .containsExactlyInAnyOrder("posSqlSessionFactory",
                        "bizSqlSessionFactory",
                        "dynamicSqlSessionFactory");
    }

    @Test
    @DisplayName("自动配置那套默认 bean 名字一个都不存在")
    void noAutoConfiguredBeanNames() {
        // 单数据源时这几个名字是存在的，它们「消失」就是退让最直观的样子
        assertThat(context.containsBean("dataSource")).isFalse();
        assertThat(context.containsBean("jdbcTemplate")).isFalse();
        assertThat(context.containsBean("transactionManager")).isFalse();
        assertThat(context.containsBean("sqlSessionFactory")).isFalse();
        assertThat(context.containsBean("jdbcClient")).isFalse();
    }
}
