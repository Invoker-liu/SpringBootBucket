package com.xncoding.websocket.config;

import com.xncoding.websocket.jsr356.NotifyJsr356Endpoint;
import com.xncoding.websocket.ws.NotifyWebSocketHandler;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 两条 WS 路线的注册点：
 * Spring 路线：@EnableWebSocket + WebSocketConfigurer，握手拦截器带身份参数；
 * JSR-356 路线：@ServerEndpoint 类经 ServerEndpointConfig + SpringConfigurator 注册，
 * 内嵌 Tomcat 没有 WEB-INF/classes 可供 WsSci 扫描，注解写好不会自动生效。
 */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WsConfig.class);

    private final NotifyWebSocketHandler notifyWebSocketHandler;
    private final HandshakeAuthInterceptor handshakeAuthInterceptor;

    public WsConfig(NotifyWebSocketHandler notifyWebSocketHandler,
                    HandshakeAuthInterceptor handshakeAuthInterceptor) {
        this.notifyWebSocketHandler = notifyWebSocketHandler;
        this.handshakeAuthInterceptor = handshakeAuthInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notifyWebSocketHandler, "/ws")
                .addInterceptors(handshakeAuthInterceptor)
                .setAllowedOriginPatterns("*");
        log.info("WS handler registered at /ws");
    }

    /**
     * JSR-356 路线注册：SpringConfigurator 让每个连接的端点实例拿到 Spring 依赖。
     * 注册必须放在 ServletContextListener 的 contextInitialized 里做：
     * ServerContainer 属性由 Tomcat 的 WsSci 在 SCI 阶段写入，Boot 的
     * ServletContextInitializer 阶段它还不存在。
     */
    @Bean
    public ServletContextInitializer jsr356EndpointRegistrar() {
        return (ServletContext servletContext) -> servletContext.addListener(new jakarta.servlet.ServletContextListener() {

            @Override
            public void contextInitialized(jakarta.servlet.ServletContextEvent sce) {
                ServerContainer container = (ServerContainer)
                        sce.getServletContext().getAttribute(ServerContainer.class.getName());
                ServerEndpointConfig config = ServerEndpointConfig.Builder
                        .create(NotifyJsr356Endpoint.class, "/ws/jsr356/{user}")
                        .configurator(new BootSpringConfigurator())
                        .build();
                try {
                    container.addEndpoint(config);
                } catch (jakarta.websocket.DeploymentException e) {
                    throw new IllegalStateException("JSR-356 endpoint registration failed", e);
                }
                log.info("JSR-356 endpoint registered at /ws/jsr356/{{user}}");
            }
        });
    }
}
