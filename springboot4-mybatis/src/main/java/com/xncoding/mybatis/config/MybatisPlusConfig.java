package com.xncoding.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件注册。
 * <p>
 * 所有内置插件都挂在同一个 {@link MybatisPlusInterceptor} 上，按添加顺序组成责任链。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 乐观锁：识别实体上的 @Version，把 updateById 改写成
        // UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 分页插件。官方明确要求：配置多个插件时，分页必须放在最后一个。
        // 放在前面的话，它重写出来的 COUNT 语句会被后面的插件再改一遍，总数就不准了。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页条数兜底上限，防止有人传 size=100000 把库拖垮
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
