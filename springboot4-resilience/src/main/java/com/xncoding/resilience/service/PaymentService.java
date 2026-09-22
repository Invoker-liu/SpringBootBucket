package com.xncoding.resilience.service;

import com.xncoding.resilience.exception.ChannelUnavailableException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * 扣款服务：@Retryable 标注在接口进入点，由代理在方法外层循环重试。
 *
 * <p>includes 只对 ChannelUnavailableException 重试；maxRetries=3 表示
 * 最多额外重试 3 次（总尝试次数 = 1 + 3 = 4）；delay/multiplier/maxDelay
 * 构成指数退避：100ms、200ms、400ms 封顶 500ms。
 * 注意属性名是 maxRetries 与 includes，不是 spring-retry 的 maxAttempts 与 retryFor。
 */
@Service
public class PaymentService {

    private final FlakyPaymentGateway gateway;

    public PaymentService(FlakyPaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Retryable(
            includes = ChannelUnavailableException.class,
            maxRetries = 3,
            delay = 100,
            multiplier = 2,
            maxDelay = 500)
    public String charge(String orderId) {
        return gateway.call(orderId);
    }
}
