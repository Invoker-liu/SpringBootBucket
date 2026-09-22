package com.xncoding.jpa.service;

import com.xncoding.jpa.domain.Order;
import com.xncoding.jpa.domain.OrderItem;
import com.xncoding.jpa.domain.OrderPageQuery;
import com.xncoding.jpa.domain.OrderStatus;
import com.xncoding.jpa.dto.OrderCreateRequest;
import com.xncoding.jpa.dto.OrderItemRequest;
import com.xncoding.jpa.dto.OrderResponse;
import com.xncoding.jpa.dto.OrderStatusRequest;
import com.xncoding.jpa.dto.OrderStatusStat;
import com.xncoding.jpa.dto.OrderUpdateRequest;
import com.xncoding.jpa.dto.PageResponse;
import com.xncoding.jpa.exception.BusinessException;
import com.xncoding.jpa.exception.ResourceNotFoundException;
import com.xncoding.jpa.repository.OrderRepository;
import com.xncoding.jpa.repository.OrderSpecifications;
import com.xncoding.jpa.repository.OrderSummary;
import com.xncoding.jpa.repository.OrderSummaryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 订单业务逻辑。
 *
 * <h2>关于事务边界</h2>
 * 写方法用 {@code @Transactional}（可读写），读方法用
 * {@code @Transactional(readOnly = true)}。后者不只是"表明意图"：Spring 会把它传给
 * JDBC 连接，Hibernate 收到只读提示后会跳过脏检查、不保留持久化上下文快照，
 * 批量读场景下能省下实实在在的内存和 CPU。
 *
 * <h2>关于"实体转 DTO 的位置"</h2>
 * 所有 {@code OrderResponse.from(order)} 都写在事务方法内部。因为
 * {@code OrderResponse} 要读 {@code order.getItems()} 这个懒加载集合，
 * 事务一关就抛 {@code LazyInitializationException}。项目里
 * {@code spring.jpa.open-in-view=false}，没有那个"事务延伸到视图层"的兜底。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 订单号随机后缀用 SecureRandom 而不是 Random：订单号带时间戳，可预测的随机源等于可预测的订单号 */
    private final SecureRandom random = new SecureRandom();

    private final OrderRepository orderRepository;

    private final OrderSummaryRepository orderSummaryRepository;

    public OrderService(OrderRepository orderRepository, OrderSummaryRepository orderSummaryRepository) {
        this.orderRepository = orderRepository;
        this.orderSummaryRepository = orderSummaryRepository;
    }

    @Transactional
    public OrderResponse create(OrderCreateRequest request) {
        String orderNo = generateOrderNo();
        // 这一步是"提前给出友好错误"，挡不住并发：两个请求可能同时通过这个判断。
        // 真正兜底的是 uk_order_no 唯一索引，撞上会抛 DataIntegrityViolationException，
        // 全局异常处理器把它翻译成同样的 409。
        if (orderRepository.existsByOrderNo(orderNo)) {
            throw BusinessException.conflict("订单号已存在：" + orderNo);
        }
        Order order = new Order(orderNo, request.customerName(), request.customerPhone(),
                request.totalAmount(), request.remark());
        if (request.items() != null) {
            request.items().forEach(item -> order.addItem(toItem(item)));
        }
        return OrderResponse.from(orderRepository.save(order));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long id) {
        return OrderResponse.from(findWithItems(id));
    }

    @Transactional
    public OrderResponse update(Long id, OrderUpdateRequest request) {
        Order order = findWithItems(id);
        checkVersion(order, request.version());
        if (order.getStatus().isTerminal()) {
            throw BusinessException.unprocessable(
                    "订单已%s，不允许再修改".formatted(order.getStatus().getLabel()));
        }
        order.setCustomerName(request.customerName());
        order.setCustomerPhone(request.customerPhone());
        order.setTotalAmount(request.totalAmount());
        order.setRemark(request.remark());
        if (request.items() != null) {
            order.replaceItems(request.items().stream().map(this::toItem).toList());
        }
        // 主动 flush，让乐观锁冲突在这一行就抛出来，而不是等到事务提交。
        // 不手动 flush 的话异常照样会抛，只是发生在方法返回之后，
        // 排查时看到的堆栈会指向代理，不容易找到真正发起更新的业务代码。
        orderRepository.flush();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse changeStatus(Long id, OrderStatusRequest request) {
        Order order = findWithItems(id);
        checkVersion(order, request.version());
        if (!order.getStatus().canTransferTo(request.status())) {
            throw BusinessException.unprocessable(
                    "订单不允许从 %s 变更为 %s".formatted(
                            order.getStatus().getLabel(), request.status().getLabel()));
        }
        order.setStatus(request.status());
        orderRepository.flush();
        return OrderResponse.from(order);
    }

    @Transactional
    public void delete(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("订单", id));
        // 实体上有 @SoftDelete，这一行生成的是 UPDATE t_order SET deleted = 1，不是 DELETE。
        // CascadeType.ALL 会把删除传到明细上，明细同样是 UPDATE。
        orderRepository.delete(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> page(OrderPageQuery query) {
        Sort sort = Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, query.sortBy());
        Pageable pageable = PageRequest.of(query.page(), query.size(), sort);
        Specification<Order> spec = OrderSpecifications.allOfNullable(
                isBlank(query.keyword()) ? null : OrderSpecifications.keywordContains(query.keyword()),
                query.status() == null ? null : OrderSpecifications.hasStatus(query.status()));
        Page<Order> page = orderRepository.findAll(spec, pageable);
        // 这里是本篇 N+1 的现场：分页查出 10 个订单，然后每个订单都要再去查一次明细。
        // 事务里不会报错，只是悄悄多发 N 条 SQL。SQL 日志里数一下就知道。
        return PageResponse.of(page, OrderResponse::from);
    }

    @Transactional(readOnly = true)
    public List<OrderStatusStat> stats() {
        return orderRepository.statByStatus();
    }

    /** 只读投影：走独立的只读仓储，返回的每行只有四个字段 */
    @Transactional(readOnly = true)
    public List<OrderSummary> summaries(OrderStatus status) {
        return status == null ? List.of() : orderSummaryRepository.findByStatus(status);
    }

    /** 原生 SQL：注意仓储里那句手写的 {@code deleted = 0} */
    @Transactional(readOnly = true)
    public long countActive(OrderStatus status) {
        return orderRepository.countActiveByStatus(status.name());
    }

    /** JPQL 查询 */
    @Transactional(readOnly = true)
    public List<OrderResponse> notCheaperThan(BigDecimal minAmount) {
        return orderRepository.findNotCheaperThan(minAmount).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * 取带明细的订单。
     * <p>
     * 用 {@code findWithItemsById} 而不是 {@code findById}：后者拿到的是没有预抓取的实体，
     * 之后读 {@code getItems()} 会再补一条 SQL。单条查询下这个差别就是 1 条 SQL 和 2 条 SQL。
     */
    private Order findWithItems(Long id) {
        return orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("订单", id));
    }

    /**
     * 校验客户端带回来的版本号。
     * <p>
     * <b>这件事必须自己做，{@code @Version} 帮不上忙。</b>
     * {@code @Version} 保护的是"同一个事务里读出来又写回去"这种并发，
     * 它拿的是持久化上下文里的快照版本去比。而客户端传来的版本号是上一次请求的结果，
     * 中间隔着一次 HTTP 往返，Hibernate 根本不知道这回事，
     * 直接把值写进字段也不会触发任何检查。所以只能在服务层显式比一次。
     * <p>
     * 不传版本号就跳过校验，退化成普通更新——有些客户端确实不需要并发保护。
     */
    private void checkVersion(Order order, Integer clientVersion) {
        if (clientVersion != null && !clientVersion.equals(order.getVersion())) {
            throw BusinessException.conflict(
                    "订单已被其他人修改，请刷新后重试（当前版本 %d，提交版本 %d）"
                            .formatted(order.getVersion(), clientVersion));
        }
    }

    private OrderItem toItem(OrderItemRequest request) {
        return new OrderItem(request.productName(), request.price(), request.quantity());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 生成订单号：ORD + 17 位时间戳 + 4 位随机数，共 24 个字符，列宽 32 装得下。
     */
    private String generateOrderNo() {
        return "ORD" + LocalDateTime.now().format(ORDER_NO_TIME)
                + String.format("%04d", random.nextInt(10000));
    }
}
