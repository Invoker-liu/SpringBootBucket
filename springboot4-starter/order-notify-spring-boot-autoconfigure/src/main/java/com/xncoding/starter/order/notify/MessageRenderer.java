package com.xncoding.starter.order.notify;

/**
 * 消息渲染抽象。classpath 上有 Jackson 3 时走 JSON 渲染，没有时回退纯文本。
 * 业务也可以注册自己的 MessageRenderer bean 覆盖默认实现。
 */
public interface MessageRenderer {

    /** 把事件与订单号渲染成最终下发的内容字符串 */
    String render(String event, String orderNo);
}
