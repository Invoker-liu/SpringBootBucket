package com.xncoding.amqp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 订单异步通知演示工程：订单服务只管"下单/支付 + 发事件"，
 * 通知的投递、重试、死信兜底全部交给 RabbitMQ。
 */
@SpringBootApplication
public class AmqpApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmqpApplication.class, args);
    }
}
