package com.xncoding.redis.api;

import com.xncoding.redis.service.CaptchaService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证码 / 访问统计端到端 API 测试：真实 Redis，走完整 HTTP 栈。
 * <p>
 * 测试邮箱每次运行都带独立后缀，数据互不干扰；本次产生的键统一登记，
 * 收尾时逐个删除，并手动回退全局统计计数，不污染演示数据。
 * <p>
 * 每个用例都对"应该发生 N 次"的关键事件单独计数核对，
 * 而不是只看接口状态码。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaptchaApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String runId;

    /** 本次测试产生的全部键，收尾清理 */
    private final Set<String> createdKeys = new HashSet<>();

    @BeforeAll
    void requireRedis() {
        try {
            String pong = connectionFactory.getConnection().ping();
            Assumptions.assumeTrue("PONG".equals(pong), "Redis 不可用，整体跳过");
        } catch (Exception e) {
            Assumptions.abort("Redis 不可用，整体跳过: " + e.getMessage());
        }
        runId = Long.toHexString(System.nanoTime());
    }

    @AfterAll
    void cleanup() {
        createdKeys.forEach(k -> stringRedisTemplate.delete(k));
    }

    private String email() {
        return "tester-" + runId + "-" + System.nanoTime() + "@example.com";
    }

    /**
     * 发送一个验证码并登记键，返回从 Redis 读出的 6 位码（测试不猜值）。
     */
    private String send(String mail) throws Exception {
        mockMvc.perform(post("/api/captchas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + mail + "\"}"))
                .andExpect(status().isCreated());
        track(CaptchaService.KEY_CAPTCHA + mail);
        track(CaptchaService.KEY_LIMIT + mail);
        return readCode(mail);
    }

    private String readCode(String mail) {
        String raw = stringRedisTemplate.opsForValue().get(CaptchaService.KEY_CAPTCHA + mail);
        assertThat(raw).isNotNull();
        return raw.replaceAll(".*\"code\":\"(\\d{6})\".*", "$1");
    }

    private void track(String key) {
        createdKeys.add(key);
    }

    @Test
    @DisplayName("完整生命周期：发送 201 → 冷却期重发 429 → 状态可查 → 校验通过 → 键被删除")
    void happyLifecycle() throws Exception {
        String mail = email();

        // 1. 发送：201，带有效期与冷却秒数
        mockMvc.perform(post("/api/captchas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + mail + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresInSecond").value(300))
                .andExpect(jsonPath("$.resendAfterSecond").value(60));
        String key = CaptchaService.KEY_CAPTCHA + mail;

        // 关键事件单独核对：验证码键确实写进去了，剩余 TTL 在 (0, 300]
        Long ttl = stringRedisTemplate.getExpire(key);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(300);

        // 2. 冷却期内重发：429
        mockMvc.perform(post("/api/captchas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + mail + "\"}"))
                .andExpect(status().isTooManyRequests());

        // 3. 状态查询：TTL 剩余、5 次尝试未消耗
        mockMvc.perform(get("/api/captchas/{email}", mail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingSecond").value(ttl.intValue()))
                .andExpect(jsonPath("$.remainingAttempts").value(5));

        // 4. 校验通过：200 verified=true
        String code = readCode(mail);
        mockMvc.perform(post("/api/captchas/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + mail + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        // 关键事件单独核对：成功校验后键必须消失
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();

        // 5. 已删除再查：404（过期与删除对上层是同一种"不存在"）
        mockMvc.perform(get("/api/captchas/{email}", mail))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("错误路径：错码累计尝试次数且 TTL 保持，错满 5 次作废（403），之后 404")
    void wrongCodeAccumulatesThenInvalidates() throws Exception {
        String mail = email();
        send(mail);
        String key = CaptchaService.KEY_CAPTCHA + mail;
        Long ttlAfterSend = stringRedisTemplate.getExpire(key);

        // 错 4 次：每次 200 + verified=false，剩余尝试次数递减 5→1
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/api/captchas/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + mail + "\",\"code\":\"000000\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.verified").value(false));
            mockMvc.perform(get("/api/captchas/{email}", mail))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingAttempts").value(5 - i));
        }

        // 关键事件单独核对：错误校验不清空 TTL（写回时带上了剩余时间）
        Long ttlAfterFails = stringRedisTemplate.getExpire(key);
        assertThat(ttlAfterFails).isNotNull().isPositive();
        assertThat(ttlAfterFails).isLessThanOrEqualTo(ttlAfterSend);

        // 第 5 次错误：作废，403；作废后 404
        mockMvc.perform(post("/api/captchas/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + mail + "\",\"code\":\"000000\"}"))
                .andExpect(status().isForbidden());
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
        mockMvc.perform(get("/api/captchas/{email}", mail))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("参数校验：空邮箱 400，problem+json 带 errors 数组")
    void validationErrorIsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/captchas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.title").value("请求体校验失败"));
    }

    @Test
    @DisplayName("手动作废：DELETE 204 后键消失，再删 404")
    void invalidateThenGone() throws Exception {
        String mail = email();
        send(mail);
        String key = CaptchaService.KEY_CAPTCHA + mail;

        mockMvc.perform(delete("/api/captchas/{email}", mail))
                .andExpect(status().isNoContent());
        assertThat(stringRedisTemplate.hasKey(key)).isFalse();
        mockMvc.perform(delete("/api/captchas/{email}", mail))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("访问统计：连发两次 visits，总数恰好 +2；收尾把增量回退")
    void statsCountExactly() throws Exception {
        String mail = email();
        track("stats:visitors:" + LocalDate.now());

        MvcResult before = mockMvc.perform(get("/api/stats/visits/peek"))
                .andExpect(status().isOk()).andReturn();
        long totalBefore = Long.parseLong(readJson(before, "totalVisits"));

        // 两次带邮箱访问：总数应恰好 +2 —— 按次数核对，不做"两次都 200 就算过"
        mockMvc.perform(get("/api/stats/visits").param("email", mail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVisits").value(totalBefore + 1));
        mockMvc.perform(get("/api/stats/visits").param("email", mail))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalVisits").value(totalBefore + 2));

        // 收尾：把全局统计增量回退，演示数据的 total 不被测试抬高
        stringRedisTemplate.opsForValue().decrement("stats:visits:total", 2);
    }

    private String readJson(MvcResult result, String field) throws Exception {
        JsonNode node = JsonMapper.builder().build()
                .readTree(result.getResponse().getContentAsString());
        return node.get(field).asText();
    }
}
