package com.xncoding.transaction.event;

import java.math.BigDecimal;

/**
 * 一笔账户变动事件。
 *
 * <p>带上 {@code txName} 是必须的：监听器在事务提交<b>之后</b>才执行，
 * 那时候 {@code TransactionSynchronizationManager} 早就清理干净了，
 * 已经读不到「我是被哪个事务触发的」。事务名只能在发布事件的当下抓下来，
 * 随事件一起传过去。
 *
 * @param action 业务动作描述
 * @param amount 金额
 * @param txName 发布事件时所在事务的名字，由 Spring 生成
 */
public record AccountChangedEvent(String action, BigDecimal amount, String txName) {
}
