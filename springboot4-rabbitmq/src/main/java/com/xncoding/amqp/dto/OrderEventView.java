package com.xncoding.amqp.dto;

/**
 * 单条事件的消费轨迹视图。
 *
 * @param status  CONSUMED：消费成功；CONSUMING：重试中；DEAD：已进死信队列
 * @param attempts 该事件被投递给监听方法的总次数（首次 + 重试）
 */
public record OrderEventView(String eventId, String orderNo, String type,
                             String status, int attempts) {
}
