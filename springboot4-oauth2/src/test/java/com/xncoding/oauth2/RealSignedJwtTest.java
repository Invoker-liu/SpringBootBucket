package com.xncoding.oauth2;

import com.xncoding.oauth2.auth.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实签名链路：TokenService 签出的令牌走完整的 BearerTokenAuthenticationFilter
 * 到 JwtDecoder（Boot 自动装配的 PublicKey 路线 + aud 校验）。
 * jwt() 后处理器覆盖不到的校验器（过期、签名、受众）在这里逐项实测。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RealSignedJwtTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TokenService tokens;

    private String bearer(Jwt token) {
        return "Bearer " + token.getTokenValue();
    }

    @Test
    void valid_signed_token_passes_the_whole_chain() throws Exception {
        Jwt token = tokens.issue("reports-cron", List.of("orders:read", "orders:write"),
                TokenService.AUDIENCE, Duration.ofSeconds(600));

        mockMvc.perform(get("/api/orders").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", bearer(token))
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SK-REAL-1\",\"amount\":259.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("reports-cron"));
    }

    @Test
    void expired_token_gets_401() throws Exception {
        // 签发时间放两小时前、过期时间放两分钟前：越过默认 60 秒时钟偏移窗口
        Jwt expired = tokens.issue("reports-cron", List.of("orders:read"),
                TokenService.AUDIENCE,
                java.time.Instant.now().minusSeconds(7200),
                java.time.Instant.now().minusSeconds(120));

        mockMvc.perform(get("/api/orders").header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"));
    }

    @Test
    void tampered_signature_gets_401() throws Exception {
        Jwt token = tokens.issue("reports-cron", List.of("orders:read"),
                TokenService.AUDIENCE, Duration.ofSeconds(600));
        String tampered = token.getTokenValue();
        // 篡改签名段的首个 base64url 字符：该字符的 6 个位都是有效位，解码后签名必然不同
        int sigStart = tampered.lastIndexOf('.') + 1;
        char first = tampered.charAt(sigStart);
        String flipped = tampered.substring(0, sigStart)
                + (first == 'A' ? 'B' : 'A')
                + tampered.substring(sigStart + 1);

        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + flipped))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"));
    }

    @Test
    void wrong_audience_gets_401() throws Exception {
        Jwt other = tokens.issue("reports-cron", List.of("orders:read"),
                "other-service", Duration.ofSeconds(600));

        mockMvc.perform(get("/api/orders").header("Authorization", bearer(other)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missing_audience_gets_401() throws Exception {
        Jwt noAud = tokens.issue("reports-cron", List.of("orders:read"),
                null, Duration.ofSeconds(600));

        mockMvc.perform(get("/api/orders").header("Authorization", bearer(noAud)))
                .andExpect(status().isUnauthorized());
    }
}
