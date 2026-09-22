package com.xncoding.aimcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP Server 启动类：本工程不接任何 LLM，只做工具提供方，
 * 由 Claude Desktop / Cursor 等宿主通过 MCP 协议远程调用。
 */
@SpringBootApplication
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
