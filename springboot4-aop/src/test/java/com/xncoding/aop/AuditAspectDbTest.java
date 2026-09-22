package com.xncoding.aop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计切面落库实测：成功一行 status=OK，业务异常一行 status=ERROR，
 * 被幂等切面打回的请求不落审计行（审计在最里层，没进到就不会写）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditAspectDbTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanAudit() {
        jdbc.update("DELETE FROM t_audit_log");
    }

    private int auditRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log", Integer.class);
        return n == null ? 0 : n;
    }

    private Map<String, Object> lastAuditRow() {
        return jdbc.queryForMap(
                "SELECT module, action, method, status, error FROM t_audit_log ORDER BY id DESC LIMIT 1");
    }

    @Test
    void success_request_writes_one_ok_row() throws Exception {
        String orderNo = "SK-AUD-" + System.nanoTime();
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderNo":"%s","amount":"20.00","itemCount":1}
                                """.formatted(orderNo)))
                .andExpect(status().isCreated());

        assertThat(auditRows()).isEqualTo(1);
        Map<String, Object> row = lastAuditRow();
        assertThat(row.get("module")).isEqualTo("订单");
        assertThat(row.get("action")).isEqualTo("创建订单");
        assertThat(row.get("status")).isEqualTo("OK");
        assertThat(String.valueOf(row.get("method"))).contains("OrderController.create");
    }

    @Test
    void business_exception_writes_error_row_with_exception_name() throws Exception {
        mockMvc.perform(get("/api/orders/{orderNo}", "SK-NOPE-" + System.nanoTime()))
                .andExpect(status().isNotFound());

        assertThat(auditRows()).isEqualTo(1);
        Map<String, Object> row = lastAuditRow();
        assertThat(row.get("status")).isEqualTo("ERROR");
        assertThat(row.get("error")).isEqualTo("OrderNotFoundException");
    }

    @Test
    void idempotent_rejected_request_writes_no_audit_row() throws Exception {
        String orderNo = "SK-REJ-" + System.nanoTime();
        String body = """
                {"orderNo":"%s","amount":"5.00","itemCount":1}
                """.formatted(orderNo);
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        int afterFirst = auditRows();
        assertThat(afterFirst).isEqualTo(1);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        // 幂等切面在最外层打回，审计切面没进到，行数不变
        assertThat(auditRows()).isEqualTo(afterFirst);
    }
}
