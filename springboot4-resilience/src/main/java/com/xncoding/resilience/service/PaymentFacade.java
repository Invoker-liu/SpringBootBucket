package com.xncoding.resilience.service;

import com.xncoding.resilience.domain.ChargeResult;
import com.xncoding.resilience.exception.ChannelUnavailableException;
import org.springframework.stereotype.Service;

/**
 * 扣款门面：负责装填下游抖动、计时与降级兜底。
 *
 * <p>Framework 7 的内置弹性没有 @Recover 等价物，重试耗尽后
 * 最后一次原始异常直接抛给调用方，降级只能由外层 try/catch 自己实现，
 * 这个类就是文章里「兜底恢复」一节的载体。charge 必须经过另一个 bean
 * 的引用调用才能命中代理，同类内部 this.charge() 会绕过重试拦截器。
 */
@Service
public class PaymentFacade {

    private final PaymentService paymentService;
    private final FlakyPaymentGateway gateway;

    public PaymentFacade(PaymentService paymentService, FlakyPaymentGateway gateway) {
        this.paymentService = paymentService;
        this.gateway = gateway;
    }

    /**
     * 失败 failTimes 次后成功（failTimes 小于等于 maxRetries 时重试后成功，
     * 大于 maxRetries 时重试耗尽走降级），返回单据里带尝试次数与耗时。
     */
    public ChargeResult chargeWithFallback(String orderId, int failTimes) {
        gateway.arm(failTimes);
        long t0 = System.nanoTime();
        try {
            String txn = paymentService.charge(orderId);
            return new ChargeResult(orderId, true, gateway.getCallCount(),
                    elapsedMs(t0), false, txn);
        } catch (ChannelUnavailableException ex) {
            // 重试耗尽：返回降级单据，调用方拿到的是兜底值而不是异常
            return new ChargeResult(orderId, false, gateway.getCallCount(),
                    elapsedMs(t0), true, "degraded: " + ex.getMessage());
        }
    }

    /** 不装兜底的裸调用：重试耗尽后异常直接冒泡，用于演示原始传播路径。 */
    public ChargeResult chargeRaw(String orderId, int failTimes) {
        gateway.arm(failTimes);
        long t0 = System.nanoTime();
        try {
            String txn = paymentService.charge(orderId);
            return new ChargeResult(orderId, true, gateway.getCallCount(),
                    elapsedMs(t0), false, txn);
        } catch (ChannelUnavailableException ex) {
            throw ex;
        }
    }

    private long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}
