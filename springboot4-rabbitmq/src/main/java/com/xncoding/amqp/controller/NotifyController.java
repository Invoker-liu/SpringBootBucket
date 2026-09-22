package com.xncoding.amqp.controller;

import com.xncoding.amqp.dto.NotifyStatsView;
import com.xncoding.amqp.listener.OrderNotifyListener;
import com.xncoding.amqp.service.NotificationService;
import com.xncoding.amqp.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知链路观测 API：统计面板 + 演示入口（毒丸、故障注入）。
 */
@RestController
@RequestMapping("/api/notify")
public class NotifyController {

    private final NotificationService notificationService;
    private final OrderNotifyListener notifyListener;
    private final OrderService orderService;

    public NotifyController(NotificationService notificationService,
                            OrderNotifyListener notifyListener,
                            OrderService orderService) {
        this.notificationService = notificationService;
        this.notifyListener = notifyListener;
        this.orderService = orderService;
    }

    @GetMapping("/stats")
    public NotifyStatsView stats() {
        return notificationService.stats(10);
    }

    /** 发布毒丸事件：观察"重试 3 次 → 拒收 → 死信"的完整路径 */
    @PostMapping("/{orderNo}/poison")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String publishPoison(@PathVariable String orderNo) {
        orderService.publishPoison(orderNo);
        return "毒丸事件已发布，稍后查询 /api/notify/stats 观察死信";
    }

    /** 故障注入：让指定订单的下一次事件投递先失败 times 次（重试演示） */
    @PostMapping("/{orderNo}/inject-failure/{times}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String injectFailure(@PathVariable String orderNo, @PathVariable int times) {
        notifyListener.injectFailures(orderNo, times);
        return "已注入 " + times + " 次模拟故障";
    }

    /** 清空台账与故障注入（演示复位） */
    @DeleteMapping("/state")
    public String reset() {
        notificationService.reset();
        notifyListener.clearFailures();
        orderService.reset();
        return "演示状态已复位";
    }
}
