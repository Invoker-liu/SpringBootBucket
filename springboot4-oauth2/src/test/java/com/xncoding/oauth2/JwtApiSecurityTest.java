package com.xncoding.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 授权规则测试：jwt() 请求后处理器直接把 JwtAuthenticationToken 放进安全上下文，
 * 不经过解码器，专门验 scope 到 authority 的映射与 401/403 分工。
 * scope claim 的每个值默认映射成 SCOPE_ 前缀 authority，与资源服务器行为一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtApiSecurityTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void anonymous_request_gets_401_with_bearer_challenge() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"))
                .andExpect(jsonPath("$.title").value("未认证"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void read_scope_can_list_but_cannot_create() throws Exception {
        var read = jwt().jwt(j -> j.subject("logistics-cron")
                .claim("scope", java.util.List.of("orders:read")));

        mockMvc.perform(get("/api/orders").with(read))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders").with(read)
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SK-JWT-1\",\"amount\":99.50}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem-type:forbidden"))
                .andExpect(jsonPath("$.title").value("无权访问"));
    }

    @Test
    void write_scope_can_create_and_cancel() throws Exception {
        var write = jwt().jwt(j -> j.subject("reports-cron")
                .claim("scope", java.util.List.of("orders:read", "orders:write")));

        mockMvc.perform(post("/api/orders").with(write)
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SK-JWT-2\",\"amount\":199.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdBy").value("reports-cron"))
                .andExpect(jsonPath("$.status").value("NEW"));

        mockMvc.perform(post("/api/orders/SK-JWT-2/cancel").with(write))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void read_scope_cannot_cancel() throws Exception {
        var read = SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("logistics-cron")
                .claim("scope", java.util.List.of("orders:read")));

        mockMvc.perform(post("/api/orders/SK-JWT-X/cancel").with(read))
                .andExpect(status().isForbidden());
    }
}
