package com.xncoding.multisource.controller;

import com.xncoding.multisource.domain.Order;
import com.xncoding.multisource.domain.Product;
import com.xncoding.multisource.dto.DualDatabaseSnapshot;
import com.xncoding.multisource.dto.OrderDetail;
import com.xncoding.multisource.dto.TransactionDemoResult;
import com.xncoding.multisource.service.BizProductService;
import com.xncoding.multisource.service.PosOrderService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 方案 A（静态多数据源）的接口。
 *
 * <p>整个类里没有一个注解是跟数据源有关的 —— 数据源是在依赖注入的时候
 * 就定死的：{@link PosOrderService} 拿到的是交易库的 mapper，
 * {@link BizProductService} 拿到的是运营库的 mapper。写代码的人不需要知道
 * 有「数据源」这回事，代价是想改也改不了。
 */
@RestController
@RequestMapping("/api/static")
public class StaticDataSourceController {

    private final PosOrderService posOrderService;

    private final BizProductService bizProductService;

    public StaticDataSourceController(PosOrderService posOrderService,
                                      BizProductService bizProductService) {
        this.posOrderService = posOrderService;
        this.bizProductService = bizProductService;
    }

    @GetMapping("/orders")
    public List<Order> orders() {
        return posOrderService.list();
    }

    @GetMapping("/orders/{id}")
    public OrderDetail orderDetail(@PathVariable Long id) {
        return posOrderService.detail(id);
    }

    @GetMapping("/products")
    public List<Product> products(@RequestParam(defaultValue = "50") int maxStock) {
        return bizProductService.lowStock(maxStock);
    }

    @GetMapping("/products/{id}")
    public Product productDetail(@PathVariable Long id) {
        return bizProductService.detail(id);
    }

    /**
     * 一次请求同时读两个库，顺便让两个 mapper 各自报一次自己连的是哪儿。
     */
    @GetMapping("/snapshot")
    public DualDatabaseSnapshot snapshot() {
        return new DualDatabaseSnapshot(
                posOrderService.currentDatabase(),
                posOrderService.count(),
                bizProductService.currentDatabase(),
                bizProductService.count());
    }

    /**
     * 事务管理器绑错数据源的反例。
     *
     * <p>{@code manager=pos}（默认）：事务管理器绑的就是要写的库，异常之后回滚，数据不留。
     * <p>{@code manager=biz}：事务管理器绑的是<b>另一个</b>库。异常照样抛出、
     * 事务照样回滚，但交易库那一笔压根不在这个事务里，会留在库里。
     *
     * <p>这个接口把调用前后的订单数一起返回，就是为了让「回滚了但数据还在」
     * 这件事变成两个可以直接相减的数字，而不是一句断言。
     */
    @PostMapping("/orders/transaction-demo")
    public TransactionDemoResult transactionDemo(@RequestParam String orderNo,
                                                 @RequestParam(defaultValue = "pos") String manager) {
        boolean wrongManager = "biz".equalsIgnoreCase(manager);
        String writeTarget = posOrderService.currentDatabase();
        int before = posOrderService.count();
        String error;
        try {
            if (wrongManager) {
                posOrderService.insertThenFailWithWrongManager(orderNo);
            } else {
                posOrderService.insertThenFailCorrectly(orderNo);
            }
            error = "（没有抛异常，不符合预期）";
        } catch (IllegalStateException ex) {
            error = ex.getMessage();
        }
        int after = posOrderService.count();
        boolean rolledBack = after == before;

        String explanation = wrongManager
                ? "事务开在了 springboot4_biz 上，而 insert 走的是 springboot4_pos 自己的连接，"
                        + "不在那个事务范围内（自动提交）。所以事务确实回滚了，"
                        + "回滚的却是一个空的运营库事务，交易库这一笔留了下来。"
                : "事务管理器绑的就是交易库，insert 在那个事务里，异常触发回滚，订单数不变。";

        return new TransactionDemoResult(
                wrongManager ? "事务管理器绑错数据源（bizTransactionManager 写交易库）"
                        : "事务管理器与写入目标一致（posTransactionManager 写交易库）",
                wrongManager ? "bizTransactionManager" : "posTransactionManager",
                writeTarget,
                error,
                before,
                after,
                rolledBack,
                explanation);
    }
}
