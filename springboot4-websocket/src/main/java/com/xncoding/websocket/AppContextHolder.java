package com.xncoding.websocket;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 静态持有 ApplicationContext：JSR-356 的 Configurator 在容器线程里
 * 创建端点实例，拿不到注入，只能从这里取。
 */
@Component
public class AppContextHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static ApplicationContext get() {
        if (context == null) {
            throw new IllegalStateException("ApplicationContext not ready");
        }
        return context;
    }
}
