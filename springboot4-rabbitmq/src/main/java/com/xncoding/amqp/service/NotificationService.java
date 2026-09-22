package com.xncoding.amqp.service;

import com.xncoding.amqp.domain.OrderEvent;
import com.xncoding.amqp.dto.NotifyStatsView;
import com.xncoding.amqp.dto.OrderEventView;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通知轨迹与统计：内存版"事件台账"。
 *
 * <p>真实工程里这张表就是落库的 notify_record（幂等去重靠它），
 * 演示工程用 ConcurrentHashMap 保持链路可见。
 */
@Service
public class NotificationService {

    /** 事件状态 */
    public static final String STATUS_CONSUMED = "CONSUMED";
    public static final String STATUS_CONSUMING = "CONSUMING";
    public static final String STATUS_DEAD = "DEAD";

    /** 事件轨迹：eventId -> 轨迹记录 */
    private final Map<String, Track> tracks = new ConcurrentHashMap<>();
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong consumed = new AtomicLong();
    /** 重试发生的次数（第 2 次及以后投递都算） */
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();

    /** 单条事件的消费轨迹（attempts/status 随投递推进） */
    public static final class Track {
        public final String eventId;
        public final String orderNo;
        public final String type;
        public volatile String status = STATUS_CONSUMING;
        public volatile int attempts;

        public Track(String eventId, String orderNo, String type) {
            this.eventId = eventId;
            this.orderNo = orderNo;
            this.type = type;
        }
    }

    public void onPublished(OrderEvent event) {
        published.incrementAndGet();
        tracks.put(event.getEventId(), new Track(event.getEventId(),
                event.getOrderNo(), event.getType()));
    }

    /** 监听方法每次被投递（含重试）都会调用：返回 true 表示这是重试投递 */
    public boolean onDeliveryAttempt(OrderEvent event) {
        Track track = tracks.get(event.getEventId());
        if (track == null) {
            // 服务重启后收到旧消息：台账没记录，按重试处理并补记
            track = new Track(event.getEventId(), event.getOrderNo(), event.getType());
            tracks.put(event.getEventId(), track);
        }
        boolean isRetry = track.attempts > 0;
        track.attempts++;
        if (isRetry) {
            retried.incrementAndGet();
        }
        return isRetry;
    }

    public void onConsumed(OrderEvent event) {
        consumed.incrementAndGet();
        Track track = tracks.get(event.getEventId());
        if (track != null) {
            track.status = STATUS_CONSUMED;
        }
    }

    public void onDeadLettered(OrderEvent event) {
        deadLettered.incrementAndGet();
        Track track = tracks.get(event.getEventId());
        if (track != null) {
            track.status = STATUS_DEAD;
        }
    }

    public NotifyStatsView stats(int recentLimit) {
        List<OrderEventView> recent = tracks.values().stream()
                .sorted(Comparator.comparing(t -> t.orderNo))
                .limit(recentLimit)
                .map(t -> new OrderEventView(t.eventId, t.orderNo, t.type, t.status, t.attempts))
                .toList();
        return new NotifyStatsView(published.get(), consumed.get(), retried.get(),
                deadLettered.get(),
                com.xncoding.amqp.config.RabbitClientConfig.lastConfirmResult, recent);
    }

    /** 测试辅助：清空台账与计数器 */
    public void reset() {
        tracks.clear();
        published.set(0);
        consumed.set(0);
        retried.set(0);
        deadLettered.set(0);
    }
}
