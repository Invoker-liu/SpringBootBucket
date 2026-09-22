package com.xncoding.starter.order.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 订单通知 starter 的属性面。前缀 order.notify，三个属性：
 * enabled 总开关（关掉时整个自动配置退场）、maxRetries 失败重试次数、channel 短信渠道。
 */
@ConfigurationProperties(prefix = "order.notify")
public class OrderNotifyProperties {

    /** 是否启用订单通知，false 时自动配置整体退场，容器里不会出现任何相关 bean */
    private boolean enabled = true;

    /** 发送失败后的重试次数，0 表示只发一次不重试 */
    private int maxRetries = 2;

    /** 短信渠道：logging 走日志打桩，noop 直接丢弃（starter 内置两种，业务可整体覆盖） */
    private String channel = "logging";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
