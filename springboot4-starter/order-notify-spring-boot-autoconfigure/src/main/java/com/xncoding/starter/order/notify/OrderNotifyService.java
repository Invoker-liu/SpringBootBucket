package com.xncoding.starter.order.notify;

/**
 * 订单通知服务抽象。业务方注入它发通知；不满意默认实现时注册同类型 bean 即可整体替换。
 */
public interface OrderNotifyService {

    /**
     * 发送一条订单通知。
     *
     * @param event   事件名，例如 ORDER_CREATED
     * @param orderNo 订单号
     * @param phone   接收手机号
     */
    void notify(String event, String orderNo, String phone);
}
