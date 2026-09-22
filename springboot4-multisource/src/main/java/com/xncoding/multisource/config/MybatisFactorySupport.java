package com.xncoding.multisource.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * 三个 {@code SqlSessionFactory} 共用的构造逻辑。
 *
 * <p>抽出来不是为了少写几行，而是为了让「每个工厂之间到底差在哪」变得显眼：
 * <b>只差数据源和映射文件目录，别的必须一模一样</b>。
 * 之前踩过一个坑是三个工厂里有一个忘了设 {@code mapUnderscoreToCamelCase}，
 * 结果只有那个库查出来的字段名对不上，报错信息还指向实体类，
 * 花了很久才反应过来是工厂配置不一致。
 *
 * <p><b>{@code MybatisSqlSessionFactoryBean} 的包名是 3.5.17 才改的。</b>
 * 3.5.14 及以前在 {@code com.baomidou.mybatisplus.extension.spring} 下面，
 * 3.5.17 挪到了 {@code com.baomidou.mybatisplus.spring}。
 * 网上关于多数据源 + MyBatis-Plus 的文章几乎都还写着旧包名，
 * 照抄过来第一件事就是编译不过。
 */
final class MybatisFactorySupport {

    private MybatisFactorySupport() {
    }

    static MybatisSqlSessionFactoryBean factory(DataSource dataSource, String mapperLocationPattern)
            throws IOException {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // 下划线列名 → 驼峰属性。MyBatis 的默认值是 false，必须显式打开，
        // 不然 product_name 映射不到 productName，而且不会报错，只是字段是 null
        configuration.setMapUnderscoreToCamelCase(true);

        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setBanner(false);

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        // setGlobalConfig 不能省：BaseMapper 那套 CRUD 是靠 GlobalConfig 里的
        // SqlInjector 在解析 mapper 接口时注入进去的，不设就只有空接口。
        // 表现是 selectById 之类的方法「找不到 statement」而不是编译错误
        factory.setGlobalConfig(globalConfig);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(mapperLocationPattern));
        return factory;
    }
}
