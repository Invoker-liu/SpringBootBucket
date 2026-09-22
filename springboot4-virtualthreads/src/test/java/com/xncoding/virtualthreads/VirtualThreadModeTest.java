package com.xncoding.virtualthreads;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 虚拟线程档（spring.threads.virtual.enabled=true）回归：
 * 请求线程是 Tomcat VirtualThreadExecutor 产出的 tomcat-handler-N 虚拟线程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.threads.virtual.enabled=true")
@AutoConfigureRestTestClient
class VirtualThreadModeTest {

    @Autowired
    private RestTestClient client;

    @Test
    @DisplayName("虚拟线程档：status 报 true，请求线程是 tomcat-handler- 虚拟线程")
    void requestHandledByVirtualTomcatThread() {
        client.get().uri("/api/threads/status").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.virtualEnabled").isEqualTo("true")
                .jsonPath("$.requestThread").value(v ->
                        assertThat((String) v).contains("VirtualThread[").contains("tomcat-handler-"));
    }

    @Test
    @DisplayName("虚拟线程档：汇总接口全程运行在 tomcat-handler- 虚拟线程上")
    void summaryRunsOnVirtualTomcatThread() {
        String[] holder = new String[2];
        client.get().uri("/api/orders/o-2001/summary?delayMs=20").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestThread").value(v -> {
                    assertThat((String) v).contains("VirtualThread[").contains("tomcat-handler-");
                    holder[0] = (String) v;
                })
                .jsonPath("$.downstreams[0].thread").value(v -> holder[1] = (String) v)
                .jsonPath("$.downstreams[1].thread").value(v -> assertThat((String) v).isEqualTo(holder[0]))
                .jsonPath("$.downstreams[2].thread").value(v -> assertThat((String) v).isEqualTo(holder[0]));
        assertThat(holder[0]).isEqualTo(holder[1]);
    }

    @Test
    @DisplayName("虚拟线程档：并行版 3 个下游扇出到 3 个独立虚拟线程，耗时压缩到单次延迟附近")
    void parallelVersionFansOutThreeVirtualThreads() {
        client.get().uri("/api/orders/o-2002/summary-parallel?delayMs=20").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestThread").value(v ->
                        assertThat((String) v).contains("tomcat-handler-"))
                .jsonPath("$.downstreams[0].thread").value(v ->
                        assertThat((String) v).contains("VirtualThread[").doesNotContain("tomcat-handler-"))
                .jsonPath("$.downstreams[1].thread").value(v ->
                        assertThat((String) v).contains("VirtualThread[").doesNotContain("tomcat-handler-"))
                .jsonPath("$.downstreams[2].thread").value(v ->
                        assertThat((String) v).contains("VirtualThread[").doesNotContain("tomcat-handler-"))
                .jsonPath("$.costMs").value(v -> assertThat(((Number) v).longValue()).isLessThan(60L));
    }

    @Test
    @DisplayName("虚拟线程档：审计接口虚拟线程命名 audit-<orderId> 生效")
    void auditVirtualThreadNameHasPrefix() {
        client.post().uri("/api/orders/o-2003/audit").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.auditThread").value(v ->
                        assertThat((String) v).contains("VirtualThread[").contains("audit-o-2003"));
    }

    @Test
    @DisplayName("虚拟线程档：连续 3 个请求各占用独立虚拟线程，名称互不相同")
    void eachRequestGetsIndependentVirtualThread() {
        Set<String> threads = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            client.get().uri("/api/threads/status").exchange()
                    .expectBody()
                    .jsonPath("$.requestThread").value(v -> threads.add((String) v));
        }
        assertThat(threads).hasSize(3);
        assertThat(threads).allMatch(t -> t.contains("tomcat-handler-"));
    }
}
