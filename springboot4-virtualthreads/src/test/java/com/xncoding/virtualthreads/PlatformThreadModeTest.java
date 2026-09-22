package com.xncoding.virtualthreads;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台线程档（spring.threads.virtual.enabled=false，默认）回归：
 * 请求线程是 Tomcat 传统线程池线程 http-nio-<port>-exec-N，不是虚拟线程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.threads.virtual.enabled=false")
@AutoConfigureRestTestClient
class PlatformThreadModeTest {

    @Autowired
    private RestTestClient client;

    @Test
    @DisplayName("平台线程档：status 报 false，请求线程为 http-nio-exec 池线程")
    void requestHandledByPlatformPoolThread() {
        client.get().uri("/api/threads/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.virtualEnabled").isEqualTo("false")
                .jsonPath("$.requestThread").value(v ->
                        assertThat((String) v).contains("exec-").doesNotContain("VirtualThread"));
    }

    @Test
    @DisplayName("平台线程档：汇总接口 3 个下游全部由同一线程串行执行")
    void summaryRunsOnSinglePlatformThread() {
        String[] holder = new String[2];
        client.get().uri("/api/orders/o-1001/summary?delayMs=20").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestThread").value(v -> holder[0] = (String) v)
                .jsonPath("$.downstreams[0].thread").value(v -> holder[1] = (String) v)
                .jsonPath("$.downstreams[1].thread").value(v -> assertThat((String) v).isEqualTo(holder[0]))
                .jsonPath("$.downstreams[2].thread").value(v -> assertThat((String) v).isEqualTo(holder[0]));
        assertThat(holder[0]).isNotNull().isEqualTo(holder[1]);
        assertThat(holder[0]).contains("exec-").doesNotContain("VirtualThread");
        client.get().uri("/api/orders/o-1001/summary?delayMs=20").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.downstreams.length()").isEqualTo(3)
                .jsonPath("$.costMs").value(v -> assertThat(((Number) v).longValue()).isGreaterThanOrEqualTo(60L));
    }

    @Test
    @DisplayName("平台线程档：并行版下游仍是虚拟线程（newVirtualThreadPerTaskExecutor 与开关无关）")
    void parallelVersionStillUsesVirtualThreads() {
        client.get().uri("/api/orders/o-1002/summary-parallel?delayMs=20").exchange()
                .expectStatus().isOk()
                .expectBody()
                // 请求线程仍是平台池线程
                .jsonPath("$.requestThread").value(v ->
                        assertThat((String) v).contains("exec-").doesNotContain("VirtualThread"))
                // 扇出的 3 个下游线程是编程式虚拟线程，与开关无关
                .jsonPath("$.downstreams[0].thread").value(v ->
                        assertThat((String) v).contains("VirtualThread["))
                .jsonPath("$.downstreams[1].thread").value(v ->
                        assertThat((String) v).contains("VirtualThread["))
                .jsonPath("$.downstreams[2].thread").value(v ->
                        assertThat((String) v).contains("VirtualThread["))
                // 并行后总耗时约等于单次下游延迟，远小于 3 × 20ms 串行
                .jsonPath("$.costMs").value(v -> assertThat(((Number) v).longValue()).isLessThan(60L));
    }

    @Test
    @DisplayName("平台线程档：审计接口由 Thread.startVirtualThread 创建的虚拟线程执行")
    void auditRunsOnExplicitVirtualThread() {
        client.post().uri("/api/orders/o-1003/audit").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.auditThread").value(v ->
                        assertThat((String) v).contains("VirtualThread[").contains("audit-o-1003"));
    }
}
