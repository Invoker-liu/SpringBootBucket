package com.xncoding.orderapp;

import com.xncoding.starter.order.notify.DefaultOrderNotifyService;
import com.xncoding.starter.order.notify.OrderNotifyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 演示接口：下单后发一条通知。响应里带渲染结果与最终使用的 SmsClient 类型，
 * 供实测对账。服务用 ObjectProvider 注入：imports 错位的反例场景里
 * starter 静默缺失，应用照常启动，接口返回 STARTER_MISSING 而不是起不来。
 */
@RestController
public class NotifyController {

    private final ObjectProvider<OrderNotifyService> notifyProvider;
    private final FlakySmsConfig flakySmsConfig;

    public NotifyController(ObjectProvider<OrderNotifyService> notifyProvider,
                            FlakySmsConfig flakySmsConfig) {
        this.notifyProvider = notifyProvider;
        this.flakySmsConfig = flakySmsConfig;
    }

    @PostMapping("/api/orders/{orderNo}/notify")
    public Map<String, Object> notify(@PathVariable String orderNo) {
        OrderNotifyService notifyService = notifyProvider.getIfAvailable();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("orderNo", orderNo);
        if (notifyService == null) {
            resp.put("status", "STARTER_MISSING");
            return resp;
        }
        notifyService.notify("ORDER_CREATED", orderNo, "13800001234");
        resp.put("status", "NOTIFIED");
        if (notifyService instanceof DefaultOrderNotifyService def) {
            resp.put("content", def.getPrefix() + def.getRenderer().render("ORDER_CREATED", orderNo));
            resp.put("smsClient", def.getSmsClient().getClass().getSimpleName());
        }
        return resp;
    }

    @GetMapping("/api/ops/sms-calls")
    public Map<String, Object> smsCalls() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("totalCalls", flakySmsConfig.getTotalCalls());
        resp.put("failedCalls", flakySmsConfig.getFailedCalls());
        resp.put("okCalls", flakySmsConfig.getTotalCalls() - flakySmsConfig.getFailedCalls());
        return resp;
    }
}
