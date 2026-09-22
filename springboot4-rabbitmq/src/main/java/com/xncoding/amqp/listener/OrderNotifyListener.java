package com.xncoding.amqp.listener;

import com.xncoding.amqp.config.RabbitTopologyConfig;
import com.xncoding.amqp.domain.OrderEvent;
import com.xncoding.amqp.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知消费者：监听业务队列，把"发短信/发邮件"用日志模拟。
 *
 * <p><b>消费失败的三种结局</b>（本工程的完整演示）：
 * <ol>
 *   <li>偶发失败：抛普通异常 → yml 里的 retry 配置接管，同一消息间隔重投，
 *       重试次数耗尽前成功则正常 ack（见 flaky 演示）</li>
 *   <li>重试耗尽：默认的 RejectAndDontRequeueRecoverer 拒收且不再入队 →
 *       业务队列的 x-dead-letter-exchange 参数生效，消息进死信队列</li>
 *   <li>毒丸消息：永远失败（本演示的 POISON 类型），走完整重试 → 死信路径</li>
 * </ol>
 *
 * <p>注意参数直接就是反序列化好的 {@link OrderEvent}——JSON 转换发生在
 * 监听容器层，靠的是容器里那个唯一的 MessageConverter bean。
 */
@Component
public class OrderNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(OrderNotifyListener.class);

    private final NotificationService notificationService;

    /** flaky 演示：orderNo -> 还要失败几次（测试与演示脚本注入） */
    private final Map<String, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

    public OrderNotifyListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_NOTIFY)
    public void onOrderEvent(OrderEvent event) {
        notificationService.onDeliveryAttempt(event);
        AtomicInteger left = failuresLeft.get(event.getOrderNo());
        if (left != null && left.getAndDecrement() > 0) {
            // 偶发故障：抛普通异常，触发 yml 里的 retry（重试耗尽前消息留在队列里）
            log.warn("[通知服务] 模拟偶发失败，投递暂缓: eventId={}", event.getEventId());
            throw new IllegalStateException("模拟通知渠道抖动: " + event.getEventId());
        }
        if (OrderEvent.TYPE_POISON.equals(event.getType())) {
            // 毒丸：永远失败，最终被 RejectAndDontRequeueRecoverer 拒收 → 死信
            log.error("[通知服务] 毒丸事件，永远无法消费: {}", event.getEventId());
            throw new PoisonMessageException("毒丸消息，拒绝消费: " + event.getEventId());
        }
        doNotify(event);
        notificationService.onConsumed(event);
        log.info("[通知服务] 通知发送成功: type={}, orderNo={}, eventId={}",
                event.getType(), event.getOrderNo(), event.getEventId());
    }

    /** 死信消费者：兜底台账（真实工程：告警 + 人工/定时补偿） */
    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_DEAD)
    public void onDeadLetter(OrderEvent event) {
        notificationService.onDeadLettered(event);
        log.error("[死信兜底] 事件进入死信队列，待人工处理: type={}, orderNo={}, eventId={}",
                event.getType(), event.getOrderNo(), event.getEventId());
    }

    private void doNotify(OrderEvent event) {
        // 模拟真实的通知动作（短信/邮件/Webhook）
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 测试/演示辅助：让下一次对指定订单的投递先失败 N 次 */
    public void injectFailures(String orderNo, int times) {
        failuresLeft.put(orderNo, new AtomicInteger(times));
    }

    public void clearFailures() {
        failuresLeft.clear();
    }

    /** 专用于"永远失败"的异常类型：语义清晰，日志好认 */
    public static class PoisonMessageException extends RuntimeException {
        public PoisonMessageException(String message) {
            super(message);
        }
    }
}
