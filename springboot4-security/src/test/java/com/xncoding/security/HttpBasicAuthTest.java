package com.xncoding.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实认证链路：Basic 凭据 -> DaoAuthenticationProvider -> JdbcUserDetailsManager
 * -> sec_users 的 {bcrypt} 密文。认证成功 / 失败事件落在 AUTH_EVENT 日志行上。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class HttpBasicAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAudit() {
        jdbc.update("DELETE FROM t_audit_log");
    }

    @Test
    void correct_credentials_reach_admin_resource(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/audit").with(httpBasic("admin", "admin123")))
                .andExpect(status().isOk());

        assertThat(output).contains("AUTH_EVENT success principal=admin");
    }

    @Test
    void wrong_password_gets_401_with_failure_event(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/audit").with(httpBasic("admin", "badpass")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:unauthorized"))
                .andExpect(jsonPath("$.title").value("未登录"));

        assertThat(output).contains("AUTH_EVENT failure principal=admin");
        assertThat(output).contains("reason=BadCredentialsException");
    }

    @Test
    void unknown_user_also_401_and_hidden_as_bad_credentials(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/audit").with(httpBasic("ghost", "whatever")))
                .andExpect(status().isUnauthorized());

        // 用户不存在对外伪装成凭据错误，不给探测账号清单的人留线索
        assertThat(output).contains("reason=BadCredentialsException");
    }

    @Test
    void operator_credentials_do_not_grant_admin_resource() throws Exception {
        mockMvc.perform(get("/api/audit").with(httpBasic("operator", "operator123")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:problem-type:forbidden"));
    }

    @Test
    void malformed_basic_header_gets_401() throws Exception {
        mockMvc.perform(get("/api/audit").header("Authorization", "Basic !not-base64!"))
                .andExpect(status().isUnauthorized());
    }
}
