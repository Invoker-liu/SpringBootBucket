package com.xncoding.starter.order.notify;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单短消息通知 starter 的自动配置入口。
 * 全类挂在 order.notify.enabled 开关下，缺省视为开启；false 时整类跳过，
 * 容器里不会出现任何本 starter 的 bean。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "order.notify", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(OrderNotifyProperties.class)
public class OrderNotifyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SmsClient.class)
    @ConditionalOnProperty(prefix = "order.notify", name = "channel", havingValue = "logging", matchIfMissing = true)
    public SmsClient loggingSmsClient() {
        return new LoggingSmsClient();
    }

    @Bean
    @ConditionalOnMissingBean(SmsClient.class)
    @ConditionalOnProperty(prefix = "order.notify", name = "channel", havingValue = "noop")
    public SmsClient noopSmsClient() {
        return new NoopSmsClient();
    }

    @Bean
    @ConditionalOnMissingBean(OrderNotifyService.class)
    public OrderNotifyService defaultOrderNotifyService(OrderNotifyProperties properties,
                                                        SmsClient smsClient,
                                                        MessageRenderer renderer,
                                                        ObjectProvider<OrderNotifyCustomizer> customizers) {
        DefaultOrderNotifyService service = new DefaultOrderNotifyService(smsClient, renderer, properties);
        customizers.orderedStream().forEach(c -> c.customize(service));
        return service;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObjectMapper.class)
    static class JacksonRendererConfiguration {

        @Bean
        @ConditionalOnMissingBean(MessageRenderer.class)
        MessageRenderer jacksonMessageRenderer(ObjectMapper objectMapper) {
            return new JacksonMessageRenderer(objectMapper);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("tools.jackson.databind.ObjectMapper")
    static class PlainRendererConfiguration {

        @Bean
        @ConditionalOnMissingBean(MessageRenderer.class)
        MessageRenderer plainMessageRenderer() {
            return new PlainMessageRenderer();
        }
    }
}
