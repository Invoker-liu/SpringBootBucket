package com.xncoding.amqp.dto;

import java.util.List;

/**
 * 统计面板：整条链路的关键计数 + 发送方 confirm 结果。
 */
public record NotifyStatsView(long published, long consumed, long retried,
                              long deadLettered, String lastConfirmResult,
                              List<OrderEventView> recentEvents) {
}
