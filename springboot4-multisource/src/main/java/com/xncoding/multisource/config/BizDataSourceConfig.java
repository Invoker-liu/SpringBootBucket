package com.xncoding.multisource.config;

import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.zaxxer.hikari.HikariDataSource;

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

import javax.sql.DataSource;
import java.io.IOException;

/**
 * 方案 A：静态多数据源之二 —— 运营库。
 *
 * <p>和 {@link PosDataSourceConfig} 结构完全对称，只换了前缀、库和 mapper 包。
 * 之所以一个库一个配置类，而不是塞进一个类里写六个 {@code @Bean}，
 * 是因为 {@code @MapperScan} 是<b>类级</b>注解、一个类只能声明一组扫描规则；
 * 拆开之后「哪个 mapper 包绑哪个工厂」在文件层面就一目了然。
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.xncoding.multisource.mapper.biz",
        sqlSessionFactoryRef = "bizSqlSessionFactory")
public class BizDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.datasource.biz")
    public DataSourceProperties bizDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("app.datasource.biz.hikari")
    public HikariDataSource bizDataSource(DataSourceProperties bizDataSourceProperties) {
        return bizDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    public DataSourceInitializer bizDataSourceInitializer(
            @Qualifier("bizDataSource") DataSource bizDataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(bizDataSource);
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(
                new ClassPathResource("sql/biz-schema.sql"),
                new ClassPathResource("sql/biz-data.sql")));
        return initializer;
    }

    @Bean
    public MybatisSqlSessionFactoryBean bizSqlSessionFactory(
            @Qualifier("bizDataSource") DataSource bizDataSource) throws IOException {
        return MybatisFactorySupport.factory(bizDataSource, "classpath*:/mapper/biz/*.xml");
    }

    /**
     * 运营库专属的事务管理器。
     *
     * <p><b>这个 bean 是本篇最容易被绕进去的一个点。</b>
     * 它的事务是绑在 {@code bizDataSource} 上的。如果某个写交易库的方法
     * 误用了它（{@code @Transactional("bizTransactionManager")} 却往交易库写），
     * Spring 会在运营库上开一个事务，而交易库那次 insert 走的是自己的连接、
     * 自动提交，<b>根本不在这个事务里</b>。
     * 结果是方法抛异常、日志显示事务回滚了，交易库的数据却稳稳地留在那儿。
     * 这个场景在工程里有对应的接口可以真的跑一遍，见
     * {@code StaticDataSourceController#wrongTransactionManager}。
     */
    @Bean
    public JdbcTransactionManager bizTransactionManager(
            @Qualifier("bizDataSource") DataSource bizDataSource) {
        return new JdbcTransactionManager(bizDataSource);
    }
}
