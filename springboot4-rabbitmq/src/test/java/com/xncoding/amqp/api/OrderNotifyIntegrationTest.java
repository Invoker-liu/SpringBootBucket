package com.xncoding.amqp.api;

import com.jayway.jsonpath.JsonPath;
import com.xncoding.amqp.config.RabbitClientConfig;
import com.xncoding.amqp.config.RabbitTopologyConfig;
import com.xncoding.amqp.service.NotificationService;
import com.xncoding.amqp.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全链路集成测试：真实树莓派 broker（RabbitMQ 4.3.6）。
 *
 * <p>覆盖：发布/消费 JSON 事件、发送方 confirm、偶发失败重试、
 * 毒丸消息进死信队列、API 错误语义（404/409/400）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderNotifyIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private com.xncoding.amqp.listener.OrderNotifyListener notifyListener;

    @BeforeEach
    void cleanSlate() {
        // 队列清空（隔离上一轮残留）+ 台账复位，保证计数从零开始可断言
        rabbitAdmin.purgeQueue(RabbitTopologyConfig.QUEUE_NOTIFY, false);
        rabbitAdmin.purgeQueue(RabbitTopologyConfig.QUEUE_DEAD, false);
        notificationService.reset();
        orderService.reset();
        notifyListener.clearFailures();
    }

    /** 轮询直到断言通过或超时（异步链路断言专用） */
    private static void untilAsserted(Duration timeout, Runnable assertion) {
        AssertionError last = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                last = e;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last != null ? last : new AssertionError("等待超时");
    }

    @Test
    @Order(1)
    void converterIsJackson3AndWiredEverywhere() {
        // 制品实证的机制：唯一的 MessageConverter bean 自动挂到发送与接收两侧
        assertThat(rabbitTemplate.getMessageConverter()).isInstanceOf(JacksonJsonMessageConverter.class);
    }

    @Test
    @Order(2)
    void createOrderPublishesAndConsumesOnce() throws Exception {
        String body = "{\"product\":\"机械键盘\",\"receiver\":\"熊大\"}";
        String response = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        String orderNo = JsonPath.parse(response).read("$.orderNo");

        // 发送方可靠性：broker 已回执
        untilAsserted(Duration.ofSeconds(5),
                () -> assertThat(RabbitClientConfig.lastConfirmResult).isEqualTo("ACK"));

        // 异步消费：通知服务最终收到并消费成功，且只投递一次（没有重试）
        untilAsserted(Duration.ofSeconds(5), () -> {
            var stats = notificationService.stats(10);
            assertThat(stats.published()).isEqualTo(1);
            assertThat(stats.consumed()).isEqualTo(1);
            assertThat(stats.retried()).isZero();
            assertThat(stats.recentEvents().get(0).status()).isEqualTo("CONSUMED");
            assertThat(stats.recentEvents().get(0).attempts()).isEqualTo(1);
            assertThat(stats.recentEvents().get(0).orderNo()).isEqualTo(orderNo);
        });
    }

    @Test
    @Order(3)
    void payFlowAndConflict() throws Exception {
        String created = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"机械键盘\",\"receiver\":\"熊大\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderNo = JsonPath.parse(created).read("$.orderNo");

        mvc.perform(post("/api/orders/" + orderNo + "/pay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // 重复支付：409
        mvc.perform(post("/api/orders/" + orderNo + "/pay"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("业务规则不满足"));

        untilAsserted(Duration.ofSeconds(5),
                () -> assertThat(notificationService.stats(10).consumed()).isEqualTo(2));
    }

    @Test
    @Order(4)
    void apiErrorSemantics() throws Exception {
        mvc.perform(get("/api/orders/NO_SUCH"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("资源不存在"));

        mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"\",\"receiver\":\"熊大\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty());
    }

    @Test
    @Order(5)
    void transientFailureIsRetriedThenConsumed() throws Exception {
        String created = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"无线耳机\",\"receiver\":\"熊二\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderNo = JsonPath.parse(created).read("$.orderNo");

        // 先等 ORDER_CREATED 消费完成，再注入故障——保证故障只落在随后支付的 PAID 事件上
        untilAsserted(Duration.ofSeconds(5),
                () -> assertThat(notificationService.stats(10).consumed()).isEqualTo(1));

        mvc.perform(post("/api/notify/" + orderNo + "/inject-failure/1"))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/orders/" + orderNo + "/pay"))
                .andExpect(status().isOk());

        untilAsserted(Duration.ofSeconds(10), () -> {
            var stats = notificationService.stats(10);
            // 两条事件都被消费：ORDER_CREATED 一次成功 + ORDER_PAID 重试后成功
            assertThat(stats.consumed()).isEqualTo(2);
            assertThat(stats.retried()).isEqualTo(1);
            var event = stats.recentEvents().stream()
                    .filter(e -> e.orderNo().equals(orderNo) && e.type().equals("ORDER_PAID"))
                    .findFirst().orElseThrow();
            assertThat(event.attempts()).isEqualTo(2);
            assertThat(event.status()).isEqualTo("CONSUMED");
        });
    }

    @Test
    @Order(6)
    void poisonMessageEndsInDeadLetterQueue() throws Exception {
        String created = mvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"毒丸礼盒\",\"receiver\":\"光头强\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderNo = JsonPath.parse(created).read("$.orderNo");

        mvc.perform(post("/api/notify/" + orderNo + "/poison"))
                .andExpect(status().isAccepted());

        // 完整路径：重试 3 次（500ms → 750ms）→ 拒收不回队 → 死信进 sb4.order.dead
        untilAsserted(Duration.ofSeconds(15), () -> {
            var stats = notificationService.stats(10);
            assertThat(stats.deadLettered()).isEqualTo(1);
            var event = stats.recentEvents().stream()
                    .filter(e -> e.orderNo().equals(orderNo) && e.type().equals("POISON"))
                    .findFirst().orElseThrow();
            // Boot 4 重试底层换成 Framework 7 的 RetryTemplate：
            // max-attempts=3 实测语义是"初始 1 次 + 重试 3 次 = 共 4 次投递"
            assertThat(event.attempts()).isEqualTo(4);
            assertThat(event.status()).isEqualTo("DEAD");
        });
    }
}
