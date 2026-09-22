package com.xncoding.amqp.config;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客户端配置：消息转换器 + 发送方可靠性回调。
 *
 * <p><b>关键机制（Boot 4 制品实证）</b>：容器里只要存在<b>唯一</b>的
 * {@link MessageConverter} bean，自动配置就会把它 set 进 RabbitTemplate
 * 和监听容器工厂——这里没有任何一行"手动 setMessageConverter 给监听容器"的代码，
 * 消费端收到的也已经是 JSON 反序列化好的对象。
 *
 * <p><b>用 Jackson 3</b>：Spring AMQP 4.1 里转换器两套并存——
 * 旧 {@code Jackson2JsonMessageConverter}（com.fasterxml）与新的
 * {@code JacksonJsonMessageConverter}（tools.jackson）。Boot 4 的 web 工程
 * 自带 Jackson 3，选新不选旧。构造参数传信任包前缀，收窄 __TypeId__ 反序列化面。
 */
@Configuration
public class RabbitClientConfig {

    /** 发送方可靠性的落地证据：最近一次 confirm/return 结果，由 GET /api/stats 暴露 */
    public static volatile String lastConfirmResult = "N/A";

    @Bean
    public MessageConverter jacksonJsonMessageConverter() {
        // trustedPackages：只允许反序列化自己工程的类型（对应 __TypeId__ 头）
        return new JacksonJsonMessageConverter("com.xncoding.");
    }

    /**
     * RabbitTemplateCustomizer：Boot 4 定制自动配置 RabbitTemplate 的官方口子。
     * yml 开 publisher-confirm-type=correlated 后，broker 会对每条消息回执 ack/nack；
     * mandatory=true 时路由不到任何队列的消息会触发 ReturnsCallback，而不是静默丢弃。
     */
    @Bean
    public RabbitTemplateCustomizer reliabilityCustomizer() {
        return template -> {
            template.setConfirmCallback((correlationData, ack, cause) ->
                    lastConfirmResult = ack ? "ACK" : "NACK: " + cause);
            template.setReturnsCallback(returned ->
                    lastConfirmResult = "RETURNED: " + returned.getReplyText());
        };
    }
}
