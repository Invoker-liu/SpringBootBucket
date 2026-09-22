package com.xncoding.starter.order.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空实现短信网关：只记一条 DEBUG，不做任何外发。测试与灰度环境用它静音。
 */
public class NoopSmsClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(NoopSmsClient.class);

    @Override
    public void send(String phone, String content) {
        log.debug("SMS_NOOP phone={} content={}", phone, content);
    }
}
