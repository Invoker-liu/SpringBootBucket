package com.xncoding.nativeapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import com.xncoding.nativeapp.config.ShopProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文与配置属性绑定测试：ShopProperties 是 AOT 反射处理的代表性点位。
 */
@SpringBootTest
class ShopPropertiesTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("@ConfigurationProperties 绑定 yml 全部三个字段")
    void binding() {
        ShopProperties props = context.getBean(ShopProperties.class);
        assertThat(props.getName()).isEqualTo("xncoding-shop");
        assertThat(props.getVipDiscount()).isEqualTo(0.88);
        assertThat(props.getBulkThreshold()).isEqualTo(9999);
    }

    @Test
    @DisplayName("核心 Bean 全部存在：控制器、服务、配置属性")
    void coreBeans() {
        // @ConfigurationPropertiesScan 注册的 bean 名为 shop-<全限定类名>，用类型断言
        assertThat(context.containsBean("orderController")).isTrue();
        assertThat(context.containsBean("orderService")).isTrue();
        assertThat(context.getBean(ShopProperties.class)).isNotNull();
    }
}
