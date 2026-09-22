package com.xncoding.websocket.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 通知中心的 WS 处理器：TextWebSocketHandler 只走文本帧，
 * 二进制帧走父类默认实现（直接抛 CloseStatus.NOT_ACCEPTABLE 断开）。
 */
@Component
public class NotifyWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NotifyWebSocketHandler.class);

    private final SessionRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public NotifyWebSocketHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String user = (String) session.getAttributes().get("user");
        registry.register(user == null ? "anonymous" : user, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asString("");
        if ("ping".equals(type)) {
            registry.onPing(session);
            return;
        }
        log.info("WS_TEXT user={} sessionId={} type={} payload={}",
                session.getAttributes().get("user"), session.getId(), type, message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String user = (String) session.getAttributes().get("user");
        registry.unregister(user == null ? "anonymous" : user, session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
        log.warn("WS_ERROR sessionId={} error={}", session.getId(), exception.toString());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
