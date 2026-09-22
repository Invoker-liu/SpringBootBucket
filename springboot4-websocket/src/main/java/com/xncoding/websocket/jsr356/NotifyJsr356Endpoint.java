package com.xncoding.websocket.jsr356;

import com.xncoding.websocket.ws.SessionRegistry;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

/**
 * JSR-356 对照路线：@ServerEndpoint + @OnOpen/@OnMessage/@OnClose/@OnError。
 * 路径参数 {user} 从 session.getPathParameters() 取；
 * SpringConfigurator 负责给每个连接新建实例并完成 @Autowired 注入。
 */
@ServerEndpoint(value = "/ws/jsr356/{user}")
public class NotifyJsr356Endpoint {

    private static final Logger log = LoggerFactory.getLogger(NotifyJsr356Endpoint.class);

    @Autowired
    private SessionRegistry registry;

    @OnOpen
    public void onOpen(Session session, @PathParam("user") String user) {
        session.getUserProperties().put("user", user);
        log.info("JSR_OPEN user={} sessionId={}", user, session.getId());
    }

    @OnMessage
    public String onMessage(String message, Session session) {
        String user = (String) session.getUserProperties().getOrDefault("user", "anonymous");
        return "echo:" + user + ":" + message;
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        String user = (String) session.getUserProperties().getOrDefault("user", "anonymous");
        log.info("JSR_CLOSE user={} sessionId={} reason={}", user, session.getId(), reason);
    }

    @OnError
    public void onError(Session session, Throwable error) throws IOException {
        log.warn("JSR_ERROR sessionId={} error={}", session.getId(), error.toString());
        if (session.isOpen()) {
            session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, "error"));
        }
    }

    public SessionRegistry registry() {
        return registry;
    }
}
