package com.xncoding.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口本身的行为冒烟：注解只影响文档，不影响运行时。
 * 管理接口的 401/204 用 httpBasic() 后处理器走完整过滤器链。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiSmokeTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void create_order_returns_201_with_defaults() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SK-OA3-1\",\"amount\":359.00,\"note\":\"加急\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("SK-OA3-1"))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.createdBy").value("system"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void duplicate_order_no_returns_409() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SK-OA3-DUP\",\"amount\":10.00}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SK-OA3-DUP\",\"amount\":20.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:duplicate-order"));
    }

    @Test
    void invalid_body_returns_400_with_field_errors() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"\",\"amount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void admin_delete_without_credentials_is_401() throws Exception {
        mockMvc.perform(delete("/api/admin/orders/999"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_delete_with_basic_auth_returns_204_then_404() throws Exception {
        String location = mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("{\"orderNo\":\"SK-OA3-DEL\",\"amount\":1.00}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        String id = location.substring(location.lastIndexOf('/') + 1);

        mockMvc.perform(delete("/api/admin/orders/" + id)
                        .with(httpBasic("admin", "admin-2026")))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/admin/orders/" + id)
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("admin", "admin-2026")))
                .andExpect(status().isNotFound());
    }
}
