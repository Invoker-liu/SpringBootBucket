package com.xncoding.starter.order.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认通知服务：渲染消息、按属性重试、经 SmsClient 发出。
 * 自动配置装配后，customizer 可以对实例做最后收口（例如设置业务前缀）。
 */
public class DefaultOrderNotifyService implements OrderNotifyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrderNotifyService.class);

    private final SmsClient smsClient;
    private final MessageRenderer renderer;
    private final OrderNotifyProperties properties;

    /** 业务前缀，customizer 收口点，最终拼在内容最前面 */
    private String prefix = "";

    public DefaultOrderNotifyService(SmsClient smsClient, MessageRenderer renderer,
                                     OrderNotifyProperties properties) {
        this.smsClient = smsClient;
        this.renderer = renderer;
        this.properties = properties;
    }

    @Override
    public void notify(String event, String orderNo, String phone) {
        String content = prefix + renderer.render(event, orderNo);
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                smsClient.send(phone, content);
                if (attempt > 1) {
                    log.info("NOTIFY_RETRY_OK event={} orderNo={} attempt={}/{}",
                            event, orderNo, attempt, attempts);
                }
                return;
            } catch (RuntimeException ex) {
                if (attempt == attempts) {
                    log.error("NOTIFY_GIVE_UP event={} orderNo={} attempts={}",
                            event, orderNo, attempt);
                    throw ex;
                }
                log.warn("NOTIFY_RETRY event={} orderNo={} attempt={}/{} reason={}",
                        event, orderNo, attempt, attempts, ex.getMessage());
            }
        }
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public SmsClient getSmsClient() {
        return smsClient;
    }

    public MessageRenderer getRenderer() {
        return renderer;
    }
}
