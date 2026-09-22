package com.xncoding.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层的 401 / 403 / 200 全景：匿名、OPERATOR、ADMIN 三种身份打三类接口。
 * @WithMockUser 直接把 SecurityContext 放进上下文，不经过 UserDetailsService，
 * 真实凭据链路（JDBC 用户 + bcrypt）由 HttpBasicAuthTest 单独验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAudit() {
        jdbc.update("DELETE FROM t_audit_log");
    }

    @Test
    void anonymous_gets_401_problem() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"))
                .andExpect(jsonPath("$.title").value("未登录"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void anonymous_cancel_also_401() throws Exception {
        mockMvc.perform(post("/api/orders/SK-100/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"));
    }

    @Test
    @WithMockUser(username = "op1", roles = "OPERATOR")
    void operator_can_list_and_create_orders() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("""
                                {"orderNo":"SK-OP-1","amount":"88.00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").value("SK-OP-1"));
    }

    @Test
    @WithMockUser(username = "op1", roles = "OPERATOR")
    void operator_cannot_read_audit_403_problem() throws Exception {
        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem-type:forbidden"))
                .andExpect(jsonPath("$.title").value("无权访问"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithMockUser(username = "op1", roles = "OPERATOR")
    void operator_cancel_hits_method_security_403() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("""
                                {"orderNo":"SK-OP-2","amount":"66.00"}
                                """))
                .andExpect(status().isCreated());

        // 路径级规则放 OPERATOR 过 /api/orders/**，拦截发生在服务层的 @PreAuthorize
        mockMvc.perform(post("/api/orders/SK-OP-2/cancel"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem-type:forbidden"));

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    @WithMockUser(username = "rootadmin", roles = "ADMIN")
    void admin_cancel_writes_audit_row() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType("application/json")
                        .content("""
                                {"orderNo":"SK-AD-1","amount":"129.00"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders/SK-AD-1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("rootadmin"));

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @WithMockUser(username = "rootadmin", roles = "ADMIN")
    void admin_can_read_audit() throws Exception {
        mockMvc.perform(get("/api/audit"))
                .andExpect(status().isOk());
    }
}
