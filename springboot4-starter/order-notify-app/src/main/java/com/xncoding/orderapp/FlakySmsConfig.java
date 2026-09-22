package com.xncoding.orderapp;

import com.xncoding.starter.order.notify.SmsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用自带的短信打桩网关。容器里出现这个 bean 后，starter 的 loggingSmsClient
 * 因 @ConditionalOnMissingBean 退位，让位行为正好用 /api/ops/sms-calls 观测。
 * 网关前两次发送必失败，用来实测 starter 的重试逻辑。
 */
@Configuration
public class FlakySmsConfig {

    private static final Logger log = LoggerFactory.getLogger(FlakySmsConfig.class);

    private final AtomicInteger totalCalls = new AtomicInteger();
    private final AtomicInteger failedCalls = new AtomicInteger();

    /** 具名实现而不是 lambda，日志与响应里的类名可读 */
    static class FlakySmsClient implements SmsClient {
        private final AtomicInteger totalCalls;
        private final AtomicInteger failedCalls;

        FlakySmsClient(AtomicInteger totalCalls, AtomicInteger failedCalls) {
            this.totalCalls = totalCalls;
            this.failedCalls = failedCalls;
        }

        @Override
        public void send(String phone, String content) {
            int n = totalCalls.incrementAndGet();
            if (n <= 2) {
                failedCalls.incrementAndGet();
                log.warn("STUB_SMS fail #{} phone={}", n, phone);
                throw new IllegalStateException("模拟网关抖动 #" + n);
            }
            log.info("STUB_SMS ok call#{} phone={} content={}", n, phone, content);
        }
    }

    @Bean
    public SmsClient flakySmsClient() {
        return new FlakySmsClient(totalCalls, failedCalls);
    }

    public int getTotalCalls() {
        return totalCalls.get();
    }

    public int getFailedCalls() {
        return failedCalls.get();
    }
}
