package com.xncoding.websocket.notify;

import com.xncoding.websocket.ws.SessionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知中心入口：下单即广播、运营触发广播、定向推送、运行计数。
 */
@RestController
@RequestMapping("/api")
public class NotifyController {

    private final NotifyService notifyService;
    private final SessionRegistry registry;

    public NotifyController(NotifyService notifyService, SessionRegistry registry) {
        this.notifyService = notifyService;
        this.registry = registry;
    }

    public record CreateOrderRequest(String orderNo, String amount) {
    }

    /** 下单：落库后立即向全部在线客户端广播新订单事件。 */
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> createOrder(@RequestBody CreateOrderRequest req) {
        NotifyService.Order order = notifyService.createOrder(req.orderNo(), req.amount());
        int delivered = notifyService.broadcastNewOrder(order);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNo", order.orderNo());
        body.put("amount", order.amount());
        body.put("status", order.status());
        body.put("broadcast", delivered);
        return ResponseEntity.status(201).body(body);
    }

    /** 运营后台触发：广播一条新订单事件（不落库），便于截图与延迟测量。 */
    @PostMapping("/notify/broadcast")
    public Map<String, Object> broadcast(@RequestParam(defaultValue = "WS80001") String orderNo,
                                         @RequestParam(defaultValue = "88.50") String amount) {
        NotifyService.Order order = new NotifyService.Order(orderNo, amount, "CREATED", null);
        int delivered = notifyService.broadcastNewOrder(order);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "order");
        body.put("seq", notifyService.eventSeq());
        body.put("orderNo", order.orderNo());
        body.put("amount", order.amount());
        body.put("delivered", delivered);
        return body;
    }

    /** 定向推送：只有目标用户的会话收到。 */
    @PostMapping("/notify/direct")
    public Map<String, Object> direct(@RequestParam String user,
                                      @RequestParam(defaultValue = "请复核新订单") String message) {
        return notifyService.direct(user, message);
    }

    /** 运行计数：连接数、断线事件、ping/pong、送达数，取值单的数据源。 */
    @GetMapping("/notify/stats")
    public Map<String, Object> stats() {
        return registry.stats();
    }
}
