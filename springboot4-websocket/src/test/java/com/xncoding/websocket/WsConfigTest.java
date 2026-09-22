package com.xncoding.websocket;

import com.xncoding.websocket.config.WsConfig;
import com.xncoding.websocket.jsr356.NotifyJsr356Endpoint;
import com.xncoding.websocket.ws.NotifyWebSocketHandler;
import com.xncoding.websocket.ws.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置事实：两条 WS 路线的 bean 都在容器里，注册点存在。
 */
@SpringBootTest
class WsConfigTest {

    @Autowired
    private WsConfig wsConfig;

    @Autowired
    private NotifyWebSocketHandler notifyWebSocketHandler;

    @Autowired
    private SessionRegistry registry;

    @Test
    void contextRegistersWebSocketConfigurer() {
        assertThat(wsConfig).isNotNull();
    }

    @Test
    void handlerAndRegistryBeansExist() {
        assertThat(notifyWebSocketHandler).isNotNull();
        assertThat(registry).isNotNull();
    }

    @Test
    void handlerIsTextOnlyHandler() {
        // 只走文本帧：二进制帧交给 TextWebSocketHandler 的默认实现（NOT_ACCEPTABLE 断开）
        assertThat(notifyWebSocketHandler).isInstanceOf(
                org.springframework.web.socket.handler.TextWebSocketHandler.class);
    }
}
