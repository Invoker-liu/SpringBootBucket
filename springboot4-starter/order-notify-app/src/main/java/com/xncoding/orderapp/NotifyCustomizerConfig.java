package com.xncoding.orderapp;

import com.xncoding.starter.order.notify.DefaultOrderNotifyService;
import com.xncoding.starter.order.notify.OrderNotifyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * customizer 收口示例：不覆盖默认实现，只在实例构造完成后补一个业务前缀。
 */
@Configuration
public class NotifyCustomizerConfig {

    @Bean
    public OrderNotifyCustomizer retailPrefixCustomizer() {
        return service -> service.setPrefix("[零售]");
    }
}
