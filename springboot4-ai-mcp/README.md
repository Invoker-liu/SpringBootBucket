# springboot4-ai-mcp

Spring Boot 4 教程第 30 篇配套工程：用 Spring AI 2.0.1 的注解驱动 MCP Server，把订单域业务方法暴露成 LLM 可调用的 MCP 工具。

- 作者：Xiong Neng
- 系列：《SpringBoot4 全家桶系列教程》第 30 篇
- 端口：18300

## 运行

```bash
mvn spring-boot:run
```

启动后 MCP 端点为 `http://localhost:18300/mcp`（Streamable HTTP 传输）。

## 工具清单

| 工具名 | 业务方法 | 说明 |
|---|---|---|
| `get_order_by_id` | OrderMcpTools.getOrderById | 按订单号查单笔订单 |
| `list_orders_by_customer` | OrderMcpTools.listOrdersByCustomer | 按客户ID查订单列表 |
| `create_order` | OrderMcpTools.createOrder | 创建订单 |
| `weekly_order_summary` | OrderMcpTools.weeklyOrderSummary | 近 7 天订单汇总（组合工具） |

## 宿主接入：用 Claude Desktop / Cursor 连这个 MCP server

### Claude Desktop（claude_desktop_config.json）

MCP 官方提供 `mcp-remote` 桥接，把远程 HTTP 端点转成 stdio 供宿主拉起：

```json
{
  "mcpServers": {
    "order-mcp-server": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:18300/mcp"]
    }
  }
}
```

### Cursor（mcp.json）

```json
{
  "mcpServers": {
    "order-mcp-server": {
      "url": "http://localhost:18300/mcp"
    }
  }
}
```

### Claude Code（CLI）

```bash
claude mcp add --transport http order-mcp-server http://localhost:18300/mcp
```

配置后新建会话，问「ORD-1001 这笔订单是什么」「帮客户 C-007 下单一台机械臂 4500 元」「最近一周订单情况怎么样」，宿主会列出并调用对应工具。

## 协议实测

`scripts/` 内置两个验证客户端（第 30 篇 S4 取值单来源）：

```bash
# 官方 python sdk（mcp>=2.x）
python scripts/mcp_sdk_client.py http://127.0.0.1:18300

# 手写 JSON-RPC over Streamable HTTP（零依赖，urllib）
python scripts/raw_jsonrpc_client.py http://127.0.0.1:18300
```

两者都完成 initialize 握手 → tools/list（断言 4 个工具与描述）→ tools/call（查单、建单、回读、汇总）。

## 版本组合

| 组件 | 版本 |
|---|---|
| spring-boot-starter-parent | 4.1.1 |
| spring-ai-bom | 2.0.1 |
| spring-ai-starter-mcp-server-webmvc | 2.0.1（BOM 托管） |
| MCP Java SDK | mcp-core 2.0.1（BOM 托管） |
| JDK | 21 |
