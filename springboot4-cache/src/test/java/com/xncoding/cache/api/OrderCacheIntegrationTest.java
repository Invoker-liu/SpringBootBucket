package com.xncoding.cache.api;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缓存全生命周期的 HTTP 集成测试：dbHits 计数器是裁判。
 * <p>
 * 用 JUnit {@code @Order} 串联场景（同一个 orderNo 的状态要流转），
 * 真实 Redis 不可达时 assumeTrue 跳过而不是误报失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderCacheIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String payFlowOrderNo;
    private static String cancelFlowOrderNo;
    private static String flashFlowOrderNo;

    private static long baselineHits;

    @BeforeAll
    static void requireRedis(@Autowired RedisConnectionFactory connectionFactory) {
        try {
            connectionFactory.getConnection().ping();
            assertThat(true).isTrue();
        } catch (Exception e) {
            assumeTrue(false, "Redis 不可达，跳过集成测试: " + e.getMessage());
        }
    }

    private String createOrder(String product) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("""
                                {"product": "%s", "amount": 199.00}
                                """.formatted(product)))
                .andExpect(status().isCreated())
                .andReturn();
        return com.xncoding.cache.testsupport.Json.read(result.getResponse().getContentAsString(), "orderNo");
    }

    private long statsDbHits() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(
                com.xncoding.cache.testsupport.Json.read(result.getResponse().getContentAsString(), "dbHits"));
    }

    @Test
    @Order(1)
    void createThreeOrders() throws Exception {
        payFlowOrderNo = createOrder("机械键盘");
        cancelFlowOrderNo = createOrder("显示器支架");
        flashFlowOrderNo = createOrder("USB-C 扩展坞");
        assertThat(payFlowOrderNo).startsWith("SO");
    }

    @Test
    @Order(2)
    void firstGetHitsDbSecondGetHitsCache() throws Exception {
        // 第一次：未命中，走方法体（真实查库）
        mockMvc.perform(get("/api/orders/{orderNo}", payFlowOrderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
        long hitsAfterFirst = statsDbHits();

        // 第二次：命中缓存，方法体不执行，dbHits 不再增长
        mockMvc.perform(get("/api/orders/{orderNo}", payFlowOrderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
        assertThat(statsDbHits()).as("第二次查询不应再查库").isEqualTo(hitsAfterFirst);
        baselineHits = hitsAfterFirst;
    }

    @Test
    @Order(3)
    void payUpdatesCacheInPlace() throws Exception {
        // @CachePut：写库 + 直接替换缓存。之后读到的就是 PAID，且不查库
        mockMvc.perform(post("/api/orders/{orderNo}/pay", payFlowOrderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        mockMvc.perform(get("/api/orders/{orderNo}", payFlowOrderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payTime").isNotEmpty());
        assertThat(statsDbHits()).as("@CachePut 后读取不应查库").isEqualTo(baselineHits);
    }

    @Test
    @Order(4)
    void cancelEvictsAndNextGetReloads() throws Exception {
        // 先把 cancel 流程订单加载进缓存
        mockMvc.perform(get("/api/orders/{orderNo}", cancelFlowOrderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"));
        long hitsBefore = statsDbHits();

        // @CacheEvict：缓存条目被删
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", cancelFlowOrderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 再查：缓存已空，必然重新走库，dbHits +1
        mockMvc.perform(get("/api/orders/{orderNo}", cancelFlowOrderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(statsDbHits()).as("驱逐后重新查询应再查库一次").isEqualTo(hitsBefore + 1);
    }

    @Test
    @Order(5)
    void flashCacheExpiresAfterTwoSeconds() throws Exception {
        mockMvc.perform(get("/api/orders/{orderNo}/flash", flashFlowOrderNo))
                .andExpect(status().isOk());
        long hitsAfterFirst = statsDbHits();

        // 2 秒内：命中
        mockMvc.perform(get("/api/orders/{orderNo}/flash", flashFlowOrderNo))
                .andExpect(status().isOk());
        assertThat(statsDbHits()).isEqualTo(hitsAfterFirst);

        // TTL 2 秒，睡 2.3 秒等它过期：再查重新走库
        Thread.sleep(2300);
        mockMvc.perform(get("/api/orders/{orderNo}/flash", flashFlowOrderNo))
                .andExpect(status().isOk());
        assertThat(statsDbHits()).as("TTL 过期后应重新查库").isEqualTo(hitsAfterFirst + 1);
    }

    @Test
    @Order(6)
    void evictAllClearsEveryEntry() throws Exception {
        mockMvc.perform(delete("/api/orders/cache"))
                .andExpect(status().isOk());

        // 清空后重查已缓存的订单：重新走库
        long before = statsDbHits();
        mockMvc.perform(get("/api/orders/{orderNo}", payFlowOrderNo))
                .andExpect(status().isOk());
        assertThat(statsDbHits()).as("allEntries 驱逐后应重新查库").isEqualTo(before + 1);
    }

    @Test
    @Order(7)
    void unknownOrderReturns404ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/orders/{orderNo}", "SO404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:resource-not-found"))
                .andExpect(jsonPath("$.detail").value("订单不存在: SO404"));
    }

    @Test
    @Order(8)
    void cancelPaidOrderConflicts409() throws Exception {
        // payFlow 订单在 #3 已支付：再取消应 409（业务规则不满足）
        mockMvc.perform(post("/api/orders/{orderNo}/cancel", payFlowOrderNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:business-rule-violation"));
    }
}
