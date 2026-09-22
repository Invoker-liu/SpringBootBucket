package com.xncoding.starter.order.notify;

/**
 * 默认服务的收口点。自动配置在构造 DefaultOrderNotifyService 后逐个应用
 * 容器里的 OrderNotifyCustomizer，业务用它做最后微调（前缀、额外回调等），
 * 不必为一个小改动整体覆盖默认实现。
 */
@FunctionalInterface
public interface OrderNotifyCustomizer {

    void customize(DefaultOrderNotifyService service);
}
