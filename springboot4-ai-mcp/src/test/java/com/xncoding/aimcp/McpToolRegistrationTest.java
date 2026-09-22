package com.xncoding.aimcp;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具注册测试：@McpTool 注解方法应被自动扫描并注册进 MCP server，
 * 名称与描述一个不能少——它们就是 LLM 看到的接口文档。
 */
@SpringBootTest
class McpToolRegistrationTest {

    @Autowired
    private McpSyncServer mcpSyncServer;

    @Test
    @DisplayName("注解方法被扫描注册为 4 个 MCP 工具")
    void toolsAreRegistered() {
        List<McpSchema.Tool> tools = mcpSyncServer.listTools();

        assertThat(tools).hasSize(4);
        Set<String> names = tools.stream().map(McpSchema.Tool::name).collect(java.util.stream.Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "get_order_by_id",
                "list_orders_by_customer",
                "create_order",
                "weekly_order_summary");
    }

    @Test
    @DisplayName("每个工具的 description 都不为空")
    void descriptionsArePresent() {
        List<McpSchema.Tool> tools = mcpSyncServer.listTools();

        assertThat(tools)
                .allSatisfy(tool -> {
                    assertThat(tool.description()).isNotBlank();
                    assertThat(tool.description().length()).isGreaterThan(10);
                });
        // get_order_by_id 应声明必填参数 orderId
        McpSchema.Tool getTool = tools.stream()
                .filter(t -> t.name().equals("get_order_by_id"))
                .findFirst().orElseThrow();
        assertThat(getTool.inputSchema()).isNotNull();
    }
}
