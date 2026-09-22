package com.xncoding.transaction.dto;

/**
 * 一个演示步骤的结果。
 *
 * @param label   这一步在做什么
 * @param txState 执行这一步时的事务状态摘要
 * @param detail  业务上的结果（余额变了多少、抛了什么异常）
 */
public record StepResult(String label, String txState, String detail) {
}
