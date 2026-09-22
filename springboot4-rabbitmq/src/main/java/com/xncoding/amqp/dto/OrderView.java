package com.xncoding.amqp.dto;

/**
 * 订单视图（对外只暴露只读快照）。
 */
public record OrderView(String orderNo, String product, String receiver, String status) {
}
