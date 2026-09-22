package com.xncoding.resilience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.resilience.retry.MethodRetryEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置弹性端到端测试：真实启动 Tomcat（随机端口），
 * 覆盖重试 N 次后成功、耗尽后走降级兜底、原始异常传播、
 * REJECT 拒绝可观测与 BLOCK 排队不超限五条主链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResilienceEndToEndTest {

    @LocalServerPort
    private int port;

    /** 收集容器内发布的 MethodRetryEvent，断言每次失败都有事件。 */
    static final List<MethodRetryEvent> RETRY_EVENTS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class EventCaptureConfig {
        @Bean
        EventCaptureListener eventCaptureListener() {
            return new EventCaptureListener();
        }
    }

    static class EventCaptureListener {
        @org.springframework.context.event.EventListener
        void onRetry(MethodRetryEvent event) {
            RETRY_EVENTS.add(event);
        }
    }

    private String http(String method, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + "|" + response.body();
    }

    @Test
    @DisplayName("前两轮失败第三轮成功：attempts=3 且 success=true")
    void retrySucceedsAfterFlakyAttempts() throws Exception {
        RETRY_EVENTS.clear();
        String result = http("POST", "/api/payments/charge?orderId=o1&failTimes=2");
        String[] parts = result.split("\\|", 2);
        assertThat(parts[0]).isEqualTo("200");
        assertThat(parts[1]).contains("\"success\":true")
                .contains("\"attempts\":3")
                .contains("\"fallback\":false");
        // 每次下游失败都会发布 MethodRetryEvent：两次失败两个事件
        assertThat(RETRY_EVENTS).hasSize(2);
        assertThat(RETRY_EVENTS.get(0).getFailure().getClass().getSimpleName())
                .isEqualTo("ChannelUnavailableException");
    }

    @Test
    @DisplayName("重试耗尽走降级兜底：attempts=4 且 fallback=true")
    void retryExhaustedFallsBack() throws Exception {
        RETRY_EVENTS.clear();
        String result = http("POST", "/api/payments/charge?orderId=o2&failTimes=5");
        String[] parts = result.split("\\|", 2);
        assertThat(parts[0]).isEqualTo("200");
        assertThat(parts[1]).contains("\"success\":false")
                .contains("\"attempts\":4")
                .contains("\"fallback\":true")
                .contains("degraded:");
        // 4 次尝试各发一个失败事件，耗尽后追加一个 retryAborted=true 的中止事件
        assertThat(RETRY_EVENTS).hasSize(5);
        MethodRetryEvent last = RETRY_EVENTS.get(4);
        assertThat(last.isRetryAborted()).isTrue();
        assertThat(last.getFailure().getClass().getSimpleName()).isEqualTo("RetryException");
    }

    @Test
    @DisplayName("裸调用耗尽后原始异常冒泡：接口回 502 与异常类名")
    void rawPropagationReturns502() throws Exception {
        String result = http("POST", "/api/payments/charge-raw?orderId=o3&failTimes=9");
        String[] parts = result.split("\\|", 2);
        assertThat(parts[0]).isEqualTo("502");
        assertThat(parts[1]).contains("ChannelUnavailableException");
        String aborts = http("GET", "/api/payments/raw-aborts");
        assertThat(aborts).contains("\"rawAborts\":1");
    }

    @Test
    @DisplayName("REJECT 策略：6 个并发请求出现 429 拒绝且方法体并发峰值不超 2")
    void concurrencyRejectObservable() throws Exception {
        int total = 6;
        List<String> statuses = fireConcurrent(total, "/api/hotspot/report");
        assertThat(statuses).hasSize(total);
        long ok = statuses.stream().filter(s -> s.equals("200")).count();
        long rejected = statuses.stream().filter(s -> s.equals("429")).count();
        assertThat(ok + rejected).isEqualTo(total);
        assertThat(ok).isGreaterThanOrEqualTo(1);
        // limit=2、每个请求占坑 200ms：并发重叠时必然出现拒绝
        assertThat(rejected).isGreaterThanOrEqualTo(1);
        String stats = http("GET", "/api/hotspot/stats");
        assertThat(stats).contains("\"maxInFlight\":2");
    }

    @Test
    @DisplayName("BLOCK 策略：4 个并发请求全部成功但排队串行化")
    void concurrencyBlockQueuesAllRequests() throws Exception {
        int total = 4;
        List<String> statuses = fireConcurrent(total, "/api/hotspot/task");
        assertThat(statuses).allMatch(s -> s.startsWith("200"));
        String stats = http("GET", "/api/hotspot/stats");
        assertThat(stats).contains("\"maxInFlight\":2");
    }

    private List<String> fireConcurrent(int total, String path) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final int seq = i;
            futures.add(pool.submit((Callable<String>) () -> {
                gate.await();
                return http("POST", path + "?seq=" + seq).split("\\|", 2)[0];
            }));
        }
        gate.countDown();
        List<String> statuses = new ArrayList<>();
        for (Future<String> f : futures) {
            statuses.add(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return statuses;
    }
}
