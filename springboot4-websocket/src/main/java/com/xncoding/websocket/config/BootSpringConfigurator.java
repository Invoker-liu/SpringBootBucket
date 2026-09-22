package com.xncoding.websocket.config;

import com.xncoding.websocket.AppContextHolder;
import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

/**
 * Boot 下 SpringConfigurator 不可用：它找 root WebApplicationContext
 * （ContextLoaderListener 机制），Boot 的单层上下文里查不到直接抛
 * IllegalStateException。自写 Configurator 走 AutowireCapableBeanFactory：
 * 每个连接新建端点实例并完成依赖注入，语义与 SpringConfigurator 一致。
 */
public class BootSpringConfigurator extends ServerEndpointConfig.Configurator {

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) {
        AutowireCapableBeanFactory factory =
                AppContextHolder.get().getAutowireCapableBeanFactory();
        return factory.createBean(endpointClass);
    }
}
