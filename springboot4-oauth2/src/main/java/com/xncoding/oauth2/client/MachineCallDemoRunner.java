package com.xncoding.oauth2.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 自签环回的启动演示：本应用既是资源服务器，又以 client credentials 客户端的身份
 * 调自己的受保护接口。用 --spring.profiles.active=m2m-demo 启动时执行一次，
 * 日志落 M2M_CALL 行，作为客户端链路通了的对账依据。
 */
@Component
@Profile("m2m-demo")
public class MachineCallDemoRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MachineCallDemoRunner.class);

    private final RestClient logisticsRestClient;

    public MachineCallDemoRunner(RestClient logisticsRestClient) {
        this.logisticsRestClient = logisticsRestClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.nanoTime();
        ResponseEntity<String> resp = logisticsRestClient.get()
                .uri("/api/orders")
                .retrieve()
                .toEntity(String.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("M2M_CALL client=logistics uri=/api/orders status={} elapsedMs={} body={}",
                resp.getStatusCode().value(), elapsedMs, resp.getBody());
    }
}
