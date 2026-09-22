package com.xncoding.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * 认证成功 / 失败事件监听。ProviderManager 在认证出结果时发布事件，
 * 这里打 AUTH_EVENT 前缀日志，供验证脚本与测试按行对账。
 */
@Component
public class AuthAuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditListener.class);

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        log.info("AUTH_EVENT success principal={} type={}",
                event.getAuthentication().getName(),
                event.getAuthentication().getClass().getSimpleName());
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        log.info("AUTH_EVENT failure principal={} reason={}",
                event.getAuthentication() == null ? "unknown" : event.getAuthentication().getName(),
                event.getException().getClass().getSimpleName());
    }
}
