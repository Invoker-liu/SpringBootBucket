package com.xncoding.starter.order.notify;

/**
 * 短信网关抽象。starter 内置 logging 与 noop 两个实现，业务可以注册自己的
 * SmsClient bean 整体替换（自动配置里的 @ConditionalOnMissingBean 会退位）。
 */
public interface SmsClient {

    /** 发送一条短信。实现类失败时抛 RuntimeException，由默认服务按属性重试 */
    void send(String phone, String content);
}
