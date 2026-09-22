package com.xncoding.resilience.domain;

/**
 * 一次扣款调用的完整结果单：尝试次数、耗时与是否走了降级兜底，
 * 供文章实测取值使用。
 */
public record ChargeResult(
        String orderId,
        boolean success,
        int attempts,
        long elapsedMs,
        boolean fallback,
        String message) {
}
