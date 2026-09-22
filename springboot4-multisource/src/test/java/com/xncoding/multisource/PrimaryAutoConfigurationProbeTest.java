package com.xncoding.multisource;

import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对照实验：同样只有一个数据源，但<b>给它标上</b> {@code @Primary}，
 * 整个自动配置就活过来了。
 *
 * <p>动机来自一个很自然的问题：「既然多数据源会让自动配置全退让，
 * 那我给其中一个标 {@code @Primary} 不就行了？」
 *
 * <p>答案是<b>行</b>。{@code @ConditionalOnSingleCandidate} 的完整语义是
 * 「容器里只有一个候选，<b>或者</b>有多个但恰好一个标了 {@code @Primary}}」，
 * 所以标上之后前面那些退让条件全都不成立了。
 * 这个类就是用真实启动的上下文把这件事跑一遍，
 * 和 {@link AutoConfigurationBackoffTest} 里那组「bean 不存在」的断言正好互为对照。
 *
 * <p><b>但它是不是你想要的，是另一个问题。</b>
 * 自动配置只会绑那一个 {@code @Primary} 数据源，
 * 如果你有第二个库要读，它仍然拿不到 —— 只是在动态方案的场景下，
 * 「那一个」本来就是路由数据源，第二个库藏在它内部，所以刚好成立。
 */
class PrimaryAutoConfigurationProbeTest {

    @Test
    @DisplayName("一个 @Primary 数据源足以让 JdbcTemplate / 事务管理器 / SqlSessionFactory 全部复活")
    void primaryDataSourceRevivesAutoConfiguration() {
        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(PrimaryOnlyConfig.class)
                             .web(WebApplicationType.NONE)
                             .profiles("test")
                             .run()) {

            assertThat(context.getBeanNamesForType(DataSource.class)).hasSize(1);

            // 下面这几个在 AutoConfigurationBackoffTest 里全部是「不存在」，
            // 差别只在于这里的数据源标了 @Primary
            assertThat(context.getBeanNamesForType(JdbcTemplate.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(JdbcClient.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(PlatformTransactionManager.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(SqlSessionFactory.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(TransactionTemplate.class)).hasSize(1);

            // 而且用的就是自动配置那套默认 bean 名字
            assertThat(context.containsBean("jdbcTemplate")).isTrue();
            assertThat(context.containsBean("transactionManager")).isTrue();
            assertThat(context.containsBean("sqlSessionFactory")).isTrue();
        }
    }

    /**
     * 只声明一个数据源，并且标上 {@code @Primary}。
     *
     * <p>这里没有任何 {@code @ComponentScan}，所以主工程那几个
     * {@code *DataSourceConfig} 都不会被加载 —— 这个上下文里就只有一个数据源，
     * 干净地对应「单数据源」的处境。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan(basePackages = "com.xncoding.multisource.mapper.probe")
    static class PrimaryOnlyConfig {

        @Bean
        @Primary
        @ConfigurationProperties("app.datasource.pos")
        public DataSourceProperties probeDataSourceProperties() {
            return new DataSourceProperties();
        }

        @Bean
        @Primary
        public DataSource probeDataSource(DataSourceProperties probeDataSourceProperties) {
            return probeDataSourceProperties.initializeDataSourceBuilder()
                    .type(HikariDataSource.class)
                    .build();
        }
    }
}
