package com.xncoding.resilience.listener;

import org.springframework.context.event.EventListener;
import org.springframework.resilience.retry.MethodRetryEvent;
import org.springframework.stereotype.Component;

/**
 * 重试事件监听：@Retryable 处理过程中每次目标方法抛出异常，
 * Framework 7 都会发布一个 MethodRetryEvent（含失败原因与是否放弃重试），
 * 调用方只能看到最后一次异常，这个事件是观测每一次失败的唯一窗口。
 */
@Component
public class RetryEventLogger {

    @EventListener
    public void onMethodRetry(MethodRetryEvent event) {
        System.out.printf("[retry-event] method=%s, failure=%s, retryAborted=%s%n",
                event.getMethod().getName(), event.getFailure().getClass().getSimpleName(),
                event.isRetryAborted());
    }
}
