package com.xncoding.mongo.controller;

import com.xncoding.mongo.domain.OrderPageQuery;
import com.xncoding.mongo.domain.OrderStatus;
import com.xncoding.mongo.dto.OrderCreateRequest;
import com.xncoding.mongo.dto.OrderItemRequest;
import com.xncoding.mongo.dto.OrderResponse;
import com.xncoding.mongo.dto.OrderStatusRequest;
import com.xncoding.mongo.dto.OrderStatusStat;
import com.xncoding.mongo.dto.OrderUpdateRequest;
import com.xncoding.mongo.dto.PageResponse;
import com.xncoding.mongo.dto.ProductSales;
import com.xncoding.mongo.exception.BusinessException;
import com.xncoding.mongo.repository.OrderSummary;
import com.xncoding.mongo.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 订单接口。
 * <p>
 * 路径和状态码与前三篇保持一致，方便对照底层实现的差别。
 * 唯一"长得不一样"的是主键：{@code /api/orders/6742a1f3c9e77b1a2b3c4d5e}
 * 是 MongoDB 的 ObjectId，不是自增数字。
 * <p>
 * <b>注意这里没有类级 {@code @Validated}</b>。加了它，方法参数校验会改由 AOP 代理处理，
 * 抛的是 {@code ConstraintViolationException} 而不是 {@code HandlerMethodValidationException}，
 * 全局异常处理器接不到，结果是 500 而不是 400。这是第一篇踩过的坑，后续都直接不写。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 创建订单，成功返回 201 与 Location 头。
     * <p>
     * Location 里带的是 MongoDB 生成的 ObjectId。
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse created = orderService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    /**
     * 分页查询。
     *
     * @param sort 排序表达式，形如 {@code createdAt,desc}。
     *             这里用的是<b>实体属性名</b>，不是文档里的存储名——
     *             虽然 {@code created_at} 恰好也能排出正确结果（MongoDB 允许按任意路径排序），
     *             但接口只认一种口径，见 {@link OrderPageQuery}。
     */
    @GetMapping
    public PageResponse<OrderResponse> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "页码不能为负数") int page,
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "每页条数不能小于 1")
            @Max(value = 100, message = "每页条数不能大于 100") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return orderService.page(buildQuery(keyword, status, page, size, sort));
    }

    private OrderPageQuery buildQuery(String keyword, OrderStatus status, int page, int size, String sort) {
        String sortBy = OrderPageQuery.DEFAULT_SORT_FIELD;
        boolean descending = true;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            sortBy = parts[0].trim();
            if (parts.length == 2) {
                String direction = parts[1].trim();
                if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
                    throw BusinessException.unprocessable("排序方向只支持 asc 或 desc，收到：" + direction);
                }
                descending = "desc".equalsIgnoreCase(direction);
            }
        }
        return new OrderPageQuery(keyword, status, page, size, sortBy, descending);
    }

    /**
     * 状态聚合统计，走仓储上的 {@code @Aggregation}（管道写死在注解里）。
     */
    @GetMapping("/stats")
    public List<OrderStatusStat> stats() {
        return orderService.stats();
    }

    /**
     * 状态聚合统计，走 {@code MongoTemplate} 手搭管道。
     * <p>
     * 和 {@code /stats} 的结果完全一致。两个接口都留着是为了对照两种写法的取舍：
     * 注解版短，但管道被钉死在接口上；代码版长，但可以在 Java 里按参数决定加不加某个阶段。
     */
    @GetMapping("/stats/by-template")
    public List<OrderStatusStat> statsByTemplate() {
        return orderService.statsByTemplate();
    }

    /**
     * 聚合输出别名的<b>反例</b>接口。
     * <p>
     * 和 {@code /stats/by-template} 只差一个 {@code .as()} 里的名字：
     * 这里写的是 {@code totalAmount}，而 {@link OrderStatusStat} 上标的是
     * {@code @Field("total_amount")}。结果就是 {@code totalAmount} 这一项
     * 在响应里整个消失，而且没有任何报错。
     * <p>
     * 留着它是因为这个坑光靠讲不清楚——注解版会把别名翻译成属性名，
     * fluent API 不会，两套规则不一样。亲眼看见差别最快。
     */
    @GetMapping("/stats/naive")
    public List<OrderStatusStat> statsNaive() {
        return orderService.statsNaive();
    }

    /**
     * 商品销量排行：{@code $unwind} 把明细数组摊平之后再按商品分组。
     * <p>
     * 这是文档型存储回答"跨订单按商品汇总"这类问题的标准姿势。
     */
    @GetMapping("/top-products")    public List<ProductSales> topProducts(
            @RequestParam(defaultValue = "5")
            @Min(value = 1, message = "取前几名不能小于 1")
            @Max(value = 50, message = "取前几名不能大于 50") int limit) {
        return orderService.topProducts(limit);
    }

    /**
     * 订单摘要列表（接口投影，只查四个字段）。
     */
    @GetMapping("/summary")
    public List<OrderSummary> summary(@RequestParam @NotNull(message = "状态不能为空") OrderStatus status) {
        return orderService.summaries(status);
    }

    /**
     * 手写 {@code fields} 投影的<b>反例</b>接口。
     * <p>
     * 故意暴露出来给读者看差别：同一个订单，走 {@code /summary} 回来的每个字段都齐，
     * 走这里回来的 {@code remark}、{@code items}、{@code version}、{@code createdAt}
     * 全是 null——因为那些字段压根没查。
     * 投影结果不该直接当响应体用，这是本篇"投影"那一节的现场证据。
     */
    @GetMapping("/summary/projection")
    public List<OrderResponse> projection(@RequestParam @NotNull(message = "状态不能为空") OrderStatus status) {
        return orderService.summariesByFields(status);
    }

    /**
     * 某状态下的订单数（模板层条件计数）。
     */
    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam @NotNull(message = "状态不能为空") OrderStatus status) {
        return Map.of("status", status, "count", orderService.countActive(status));
    }

    /**
     * 当前出现过的所有状态（服务端 distinct）。
     */
    @GetMapping("/statuses")
    public List<OrderStatus> statuses() {
        return orderService.distinctStatuses();
    }

    /**
     * 金额不低于指定值的订单（派生查询）。
     */
    @GetMapping("/expensive")
    public List<OrderResponse> expensive(
            @RequestParam(defaultValue = "100")
            @DecimalMin(value = "0.01", message = "金额必须大于 0") BigDecimal minAmount) {
        return orderService.notCheaperThan(minAmount);
    }

    /**
     * 按内嵌明细里的商品名反查订单。
     * <p>
     * 这一条最能说明文档型建模的特点：明细没有独立集合，
     * 用 {@code items.product_name} 这样的点号路径直接钻进去匹配数组元素。
     */
    @GetMapping("/by-item")
    public List<OrderResponse> byItem(
            @RequestParam @NotBlank(message = "商品名不能为空") String productName) {
        return orderService.findByItem(productName);
    }

    /**
     * 按主键查询。
     * <p>
     * 路径变量是 ObjectId 字符串。传一个不合法的 id 会走到 404，
     * 不是 500——服务层挡了一道，见 {@code OrderService.findExisting}。
     */
    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable String id) {
        return orderService.get(id);
    }

    /**
     * 整体更新。
     * <p>
     * 走 {@code save()} + {@code @Version}，是本篇唯一受框架乐观锁保护的写路径。
     */
    @PutMapping("/{id}")
    public OrderResponse update(@PathVariable String id, @Valid @RequestBody OrderUpdateRequest request) {
        return orderService.update(id, request);
    }

    /**
     * 状态流转。
     * <p>
     * 走 {@code findAndModify}，用"当前状态"当更新条件在服务端做原子判断。
     * 不受 {@code @Version} 保护，保护机制换成了单文档原子操作。
     */
    @PatchMapping("/{id}/status")
    public OrderResponse changeStatus(@PathVariable String id, @Valid @RequestBody OrderStatusRequest request) {
        return orderService.changeStatus(id, request);
    }

    /**
     * 追加一条明细（{@code $push}）。
     */
    @PostMapping("/{id}/items")
    public OrderResponse addItem(@PathVariable String id, @Valid @RequestBody OrderItemRequest request) {
        return orderService.addItem(id, request);
    }

    /**
     * 移除指定商品的明细（{@code $pull}）。
     * <p>
     * 返回被移除的份数。同名商品有多条时会一起被移除，这是 {@code $pull} 的语义。
     */
    @DeleteMapping("/{id}/items")
    public Map<String, Object> removeItem(@PathVariable String id,
                                         @RequestParam @NotBlank(message = "商品名不能为空") String productName) {
        return Map.of("removed", orderService.removeItem(id, productName));
    }

    /**
     * 删除订单。
     * <p>
     * <b>这里是真删，和上一篇的软删除不一样。</b>MongoDB 没有 {@code @SoftDelete}，
     * {@code deleteById} 执行的就是一条 delete 命令，文档真的消失了。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        orderService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
