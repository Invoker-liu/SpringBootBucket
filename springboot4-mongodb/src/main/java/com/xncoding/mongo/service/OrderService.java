package com.xncoding.mongo.service;

import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderItem;
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
import com.xncoding.mongo.exception.ResourceNotFoundException;
import com.xncoding.mongo.repository.OrderMongoDao;
import com.xncoding.mongo.repository.OrderRepository;
import com.xncoding.mongo.repository.OrderSummary;
import com.xncoding.mongo.repository.OrderSummaryRepository;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 订单业务逻辑。
 *
 * <h2>这里没有 {@code @Transactional}</h2>
 * 上一篇（JPA）每个方法上都有，这一篇一个都没有，是刻意的。
 * MongoDB 的事务是<b>副本集/分片集群才有的能力</b>，单机 {@code mongod} 根本不支持
 * （驱动会直接报 {@code Transaction numbers are only allowed on a replica set member or mongos}）。
 * 本篇跑在单机上，写 {@code @Transactional} 只会让每次调用多一层代理和
 * {@code MongoTransactionManager} 的存在感，到了运行期真发事务命令才炸，
 * 属于"装了个假的安全网"。没有就是没有，读代码的人一眼能看出来。
 * <p>
 * 没有事务并不意味着没有并发安全——本篇的并发控制全部落在<b>单文档原子操作</b>上：
 * <ul>
 *   <li>{@code PUT} 走 {@code save()} + {@code @Version}，靠"整份替换时校验版本"拦住并发写；</li>
 *   <li>{@code PATCH /status} 走 {@code findAndModify}，把"当前状态"作为更新条件，
 *       由 MongoDB 保证原子性——不需要事务，因为它只碰一个文档；</li>
 *   <li>{@code POST /items} 走 {@code $push}，同样是单文档原子操作，
 *       并发的两次追加不会互相覆盖。</li>
 * </ul>
 * 这三种做法背后是同一个事实：<b>MongoDB 保证单个文档上的操作是原子的。</b>
 * 把需要原子性的东西设计在一个文档里，就绕开了对跨文档事务的需求——
 * 这也是"内嵌数组"这个建模选择的真正价值。真需要跨文档事务时（比如同时改订单和库存），
 * 那才得上副本集，见 README。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 订单号随机后缀用 SecureRandom 而不是 Random：订单号带时间戳，可预测的随机源等于可预测的订单号 */
    private final SecureRandom random = new SecureRandom();

    private final OrderRepository orderRepository;

    private final OrderSummaryRepository orderSummaryRepository;

    private final OrderMongoDao orderMongoDao;

    public OrderService(OrderRepository orderRepository,
                        OrderSummaryRepository orderSummaryRepository,
                        OrderMongoDao orderMongoDao) {
        this.orderRepository = orderRepository;
        this.orderSummaryRepository = orderSummaryRepository;
        this.orderMongoDao = orderMongoDao;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    public OrderResponse create(OrderCreateRequest request) {
        String orderNo = generateOrderNo();
        // 这一步是"提前给出友好错误"，挡不住并发：两个请求可能同时通过这个判断。
        // 真正兜底的是唯一索引 uk_order_no 撞出来的 DuplicateKeyException，
        // 全局异常处理器把它翻译成同样的 409。
        // 前提仍然是那个索引真的存在 —— 也就是 auto-index-creation 得打开。
        if (orderRepository.existsByOrderNo(orderNo)) {
            throw BusinessException.conflict("订单号已存在：" + orderNo);
        }
        Order order = new Order(orderNo, request.customerName(), request.customerPhone(),
                request.totalAmount(), request.remark());
        if (request.items() != null) {
            request.items().forEach(item -> order.addItem(toItem(item)));
        }
        // save() 在文档没有 _id 时走 insert，MongoDB 生成 ObjectId 后回填到实体上。
        // 所以这一行返回之后，order.getId() 已经是那串 24 位十六进制了。
        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * 整体更新：读-改-写，靠 {@code @Version} 拦并发。
     * <p>
     * 这是本篇唯一一条受乐观锁保护的写路径。它之所以受保护，是因为走的是
     * {@code save()}——Spring Data 在保存时会拿"读出来时那份文档的版本号"
     * 去构造条件，匹配不到就抛 {@code OptimisticLockingFailureException}。
     * 而下面 {@code changeStatus} 走 {@code findAndModify}，完全绕开了这层保护，
     * 得自己想办法。两条路径的保护机制不一样，别互相代入。
     */
    public OrderResponse update(String id, OrderUpdateRequest request) {
        Order order = findExisting(id);
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
        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * 状态流转。
     * <p>
     * 实现分两段，缺一不可：
     * <ol>
     *   <li><b>先读一次，判状态机合法性。</b>"PAID 不能退回 CREATED"这种规则只有
     *       拿到当前状态才判得了，而 MongoDB 的更新条件里没法表达"流转是否合法"。</li>
     *   <li><b>再带着"刚才读到的那个状态"作为更新条件去写。</b>
     *       如果两次之间有人插队改了状态，条件就不匹配，
     *       {@code findAndModify} 返回空——这时候必须报冲突，
     *       而不是重试或者当成成功。<b>这一段是整个方法的关键</b>：
     *       它把"读到的前提"变成了"写入的条件"，
     *       用一个文档的原子更新补上了没有事务的那一块。</li>
     * </ol>
     * 顺带说一个和上一篇的差别：JPA 那篇里 {@code @Version} 在 UPDATE 时自动生效，
     * 这里因为绕开了 {@code save()}，框架的版本校验不会介入，
     * 所以{@code transferStatus} 里手动 {@code inc("version", 1)} 让版本号继续走，
     * 免得客户端下次 {@code PUT} 拿着旧版本号莫名撞 409。
     */
    public OrderResponse changeStatus(String id, OrderStatusRequest request) {
        Order order = findExisting(id);
        checkVersion(order, request.version());
        if (!order.getStatus().canTransferTo(request.status())) {
            throw BusinessException.unprocessable(
                    "订单不允许从 %s 变更为 %s".formatted(
                            order.getStatus().getLabel(), request.status().getLabel()));
        }
        Order updated = orderMongoDao
                .transferStatus(id, order.getStatus(), request.status())
                .orElseThrow(() -> BusinessException.conflict(
                        "订单状态已被其他人修改，请刷新后重试"));
        return OrderResponse.from(updated);
    }

    /**
     * 删除订单。
     * <p>
     * <b>这里是真删，和上一篇不一样。</b>JPA 那篇靠 Hibernate 7 的 {@code @SoftDelete}
     * 把 DELETE 变成 UPDATE，数据行还在；MongoDB 这边没有对应的注解，
     * {@code deleteById} 就是一条 {@code delete} 命令，文档真的没了。
     * <p>
     * 想在 MongoDB 里做软删除得自己来：加一个 {@code deleted} 字段，
     * 每次查询都带上 {@code deleted: false} 条件。听起来简单，但要把条件加到
     * <b>每一个</b>查询、聚合、计数上，漏一个就会把已删数据算进来——
     * 这正是 JPA 的 {@code @SoftDelete} 帮你做的事。
     * 也可以换个角度看：文档型存储里，需要保留的历史数据本来就该另存一个集合
     * （比如归档集合），而不是在同一份数据上打标记。
     */
    public void delete(String id) {
        Order order = findExisting(id);
        orderRepository.delete(order);
    }

    /**
     * 往明细里追加一条商品。
     * <p>
     * 走 {@code $push} 的服务端原子操作，不是"读出来改完再整份写回去"。
     * 差别在并发下很实在：两个请求同时追加不同商品，
     * "读-改-写"一定会丢掉其中一个（后写覆盖先写），
     * {@code $push} 两条都在。
     */
    public OrderResponse addItem(String id, OrderItemRequest request) {
        Order order = findExisting(id);
        if (order.getStatus().isTerminal()) {
            throw BusinessException.unprocessable(
                    "订单已%s，不允许再修改".formatted(order.getStatus().getLabel()));
        }
        Order updated = orderMongoDao.pushItem(id, toItem(request))
                .orElseThrow(() -> new ResourceNotFoundException("订单", id));
        return OrderResponse.from(updated);
    }

    /**
     * 从明细里移除指定商品（{@code $pull}）。
     *
     * @return 实际被移除的文档数（0 或 1）
     */
    public long removeItem(String id, String productName) {
        findExisting(id);
        return orderMongoDao.pullItem(id, productName);
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    public OrderResponse get(String id) {
        return OrderResponse.from(findExisting(id));
    }

    /** 分页：条件拼接交给模板层，排序字段已由 {@link OrderPageQuery} 白名单校验过 */
    public PageResponse<OrderResponse> page(OrderPageQuery query) {
        Sort sort = Sort.by(query.descending() ? Sort.Direction.DESC : Sort.Direction.ASC, query.sortBy());
        Page<Order> page = orderMongoDao.findPage(query.keyword(), query.status(), sort,
                query.page(), query.size());
        return PageResponse.of(page, OrderResponse::from);
    }

    /** 状态统计，走仓储上的 {@code @Aggregation}（管道写在注解里） */
    public List<OrderStatusStat> stats() {
        return orderRepository.statByStatus();
    }

    /** 同样的统计，走模板层手搭管道。两个结果必须一致，切片测试里逐条比对过。 */
    public List<OrderStatusStat> statsByTemplate() {
        return orderMongoDao.statByStatus();
    }

    /**
     * 故意的反例：聚合输出别名按"最自然的写法"起，于是 {@code totalAmount} 整列丢失。
     * <p>
     * 只为教学存在，接口 {@code /api/orders/stats/naive} 就是给读者看的现场。真实项目别这么写。
     */
    public List<OrderStatusStat> statsNaive() {
        return orderMongoDao.statByStatusNaive();
    }

    /** 商品销量排行（{@code $unwind} + 二次分组） */
    public List<ProductSales> topProducts(int limit) {
        return orderMongoDao.topProducts(limit);
    }

    /** 只读接口投影：返回的每行只有四个字段 */
    public List<OrderSummary> summaries(OrderStatus status) {
        return status == null ? List.of() : orderSummaryRepository.findByStatus(status);
    }

    /**
     * 手写 {@code fields} 的投影反例。
     * <p>
     * 返回的 {@code Order} 只查了四列，其余字段全是 {@code null}。
     * 这个接口留着是当反面教材的：转成 {@code OrderResponse} 之后，
     * {@code remark}、{@code items}、{@code version}、{@code createdAt}
     * 都会是 null，客户端拿到的是一份"看起来少了一半"的数据。
     * 投影结果只该在读模型里用掉，不该直接当响应体。
     */
    public List<OrderResponse> summariesByFields(OrderStatus status) {
        return status == null ? List.of() : orderRepository.findByStatusWithFields(status).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /** 条件计数 */
    public long countActive(OrderStatus status) {
        return orderMongoDao.countByStatus(status);
    }

    /** 派生查询 + 比较运算符 */
    public List<OrderResponse> notCheaperThan(BigDecimal minAmount) {
        return orderRepository.findByTotalAmountGreaterThanEqual(minAmount).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /**
     * 按内嵌数组里的商品名反查订单。
     * <p>
     * 这是文档型建模最容易被问到的一件事："明细不单独存表，
     * 怎么按商品查订单？"——答案就是这样的数组元素匹配查询。
     * 代价是没有索引时全集合扫描，而且查的是"含此商品的订单"，
     * 拿不到"这个商品在哪些订单里卖了多少件"这种商品维度的汇总
     * （那得先 {@code $unwind}，见 {@link #topProducts}）。
     */
    public List<OrderResponse> findByItem(String productName) {
        return orderRepository.findByItemProductName(productName).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /** 出现过哪些状态（服务端去重） */
    public List<OrderStatus> distinctStatuses() {
        return orderMongoDao.distinctStatuses();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 取一个一定存在的订单。
     * <p>
     * 这里的第一行是必要的防御：MongoDB 的主键是 ObjectId，
     * 把一个不合法的主键（长度不对、含非十六进制字符）交给驱动，
     * 得到的是 {@code ConversionFailedException} 而不是"查不到"。
     * 那会一路冒到全局异常处理器变成 500——一个客户端传错 URL 的问题
     * 变成了服务端错误。提前判一次，统一按 404 处理。
     * <p>
     * 用 {@link ObjectId#isValid(String)} 而不是自己写正则：
     * 它同时也接受 24 位十六进制之外的合法 ObjectId 表示（比如 12 字节的字符串形式），
     * 自己写正则会把这些合法输入误判成非法。
     */
    private Order findExisting(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw new ResourceNotFoundException("订单", id);
        }
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("订单", id));
    }

    /**
     * 校验客户端带回来的版本号。
     * <p>
     * <b>和 JPA 那篇是同一个坑，一个字没变。</b>框架的 {@code @Version} 保护的是
     * "同一个上下文里读出来又写回去"这种并发，它拿的是自己那份快照去比；
     * 而客户端传来的版本号是上一次请求的结果，中间隔着一次 HTTP 往返，
     * 框架根本不知道这回事。只能在服务层显式比一次。
     * <p>
     * 不传版本号就跳过校验，退化成普通更新——有些客户端确实不需要并发保护。
     */
    private void checkVersion(Order order, Long clientVersion) {
        if (clientVersion != null && !clientVersion.equals(order.getVersion())) {
            throw BusinessException.conflict(
                    "订单已被其他人修改，请刷新后重试（当前版本 %d，提交版本 %d）"
                            .formatted(order.getVersion(), clientVersion));
        }
    }

    private OrderItem toItem(OrderItemRequest request) {
        return new OrderItem(request.productName(), request.price(), request.quantity());
    }

    /**
     * 生成订单号：ORD + 17 位时间戳 + 4 位随机数。
     * <p>
     * 和前三篇一样的格式。MongoDB 这边没有列宽限制，之所以还用定长，
     * 是为了让唯一索引上的键长度稳定——变长键在索引里占用空间不一致，
     * 缓存命中率会差一点，属于很小的优化但顺手就做了。
     */
    private String generateOrderNo() {
        return "ORD" + LocalDateTime.now().format(ORDER_NO_TIME)
                + String.format("%04d", random.nextInt(10000));
    }
}
