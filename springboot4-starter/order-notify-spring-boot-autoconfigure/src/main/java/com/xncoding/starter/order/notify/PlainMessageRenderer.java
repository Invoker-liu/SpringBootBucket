package com.xncoding.starter.order.notify;

/**
 * 纯文本渲染：classpath 没有 Jackson 时自动配置的回退实现。
 */
public class PlainMessageRenderer implements MessageRenderer {

    @Override
    public String render(String event, String orderNo) {
        return event + ":" + orderNo;
    }
}
