package com.xncoding.starter.order.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志打桩短信网关：把发送内容打到 INFO 日志，便于演示与联调对账。
 */
public class LoggingSmsClient implements SmsClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsClient.class);

    @Override
    public void send(String phone, String content) {
        log.info("SMS_LOG phone={} content={}", phone, content);
    }
}
