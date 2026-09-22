package com.xncoding.mybatis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xncoding.mybatis.domain.Order;
import com.xncoding.mybatis.domain.OrderPageQuery;
import com.xncoding.mybatis.domain.OrderStatus;
import com.xncoding.mybatis.dto.OrderCreateRequest;
import com.xncoding.mybatis.dto.OrderResponse;
import com.xncoding.mybatis.dto.OrderStatusRequest;
import com.xncoding.mybatis.dto.OrderStatusStat;
import com.xncoding.mybatis.dto.OrderUpdateRequest;
import com.xncoding.mybatis.dto.PageResponse;
import com.xncoding.mybatis.exception.BusinessException;
import com.xncoding.mybatis.exception.ResourceNotFoundException;
import com.xncoding.mybatis.mapper.OrderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 订单业务逻辑。
 * <p>
 * 这里没有继承 MyBatis-Plus 的 {@code ServiceImpl<OrderMapper, Order>}，而是直接持有 Mapper。
 * 理由：{@code IService} 会往业务接口上挂三十多个方法，其中九成本项目用不到，
 * 却成了这个 Service 的公开契约。只暴露真正需要的方法，接口小得多，也更好读。
 * 团队如果习惯 {@code IService} / {@code ServiceImpl} 那套，用它同样没问题。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 订单号撞唯一键时的最大重试次数 */
    private static final int ORDER_NO_MAX_RETRY = 3;

    /**
     * 排序字段白名单：对外字段名 -> 实体属性引用。
     * <p>
     * 键必须与 {@link OrderPageQuery#SORTABLE_FIELDS} 保持一致。用方法引用而不是列名字符串，
     * 这样配合 LambdaQueryWrapper，属性名改了编译期就会报错，不会等到运行时才发现列名不存在。
     * <p>
     * 注意 status 存的是枚举名，按它排序出来是字母序（CANCELLED 在 CREATED 前面），
     * 不是业务上的流转顺序。真需要按业务顺序排，得另加一个数值列。
     */
    private static final Map<String, SFunction<Order, ?>> SORT_COLUMNS = Map.of(
            "id", Order::getId,
            "orderNo", Order::getOrderNo,
            "customerName", Order::getCustomerName,
            "totalAmount", Order::getTotalAmount,
            "status", Order::getStatus,
            "createdAt", Order::getCreatedAt,
            "updatedAt", Order::getUpdatedAt);

    private final OrderMapper orderMapper;

    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 创建订单。
     * <p>
     * 订单号由服务端生成，撞上唯一键就换一个重试——并发下这事儿概率不高但一定会发生，
     * 与其等线上报警，不如在代码里解决掉。
     */
    public OrderResponse create(OrderCreateRequest request) {
        for (int attempt = 0; attempt < ORDER_NO_MAX_RETRY; attempt++) {
            Order order = new Order();
            order.setOrderNo(nextOrderNo());
            order.setCustomerName(request.customerName());
            order.setCustomerPhone(request.customerPhone());
            order.setTotalAmount(request.totalAmount());
            order.setStatus(OrderStatus.CREATED);
            order.setRemark(request.remark());
            // 明细由 typeHandler 序列化成 JSON 写进一个列，这里不用自己转字符串
            order.setItems(request.items());

            try {
                // 主键自增，插入后 order.getId() 已经被回填，不用再查一次
                orderMapper.insert(order);
                return OrderResponse.from(order);
            } catch (DuplicateKeyException ex) {
                // 订单号重复，换一个再来
            }
        }
        throw BusinessException.conflict("订单号生成冲突，请稍后重试");
    }

    /** 按主键查询，不存在时抛 404 */
    public OrderResponse get(Long id) {
        return OrderResponse.from(requireById(id));
    }

    /**
     * 分页查询。
     * <p>
     * 分页插件会自动把这条查询改写成带 LIMIT 的语句，并额外执行一次 COUNT。
     */
    public PageResponse<OrderResponse> page(OrderPageQuery query) {
        // MyBatis-Plus 的页码从 1 开始，对外契约是 0 基，两边的差异只在这一行里消化
        Page<Order> page = new Page<>(query.page() + 1L, query.size());

        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                // 条件构造器的第一个布尔参数是"是否拼接这个条件"，
                // 有了它就不用把 SQL 拼成一堆 if-else
                //
                // 关键字同时匹配订单号和客户姓名。这里必须用 .and(...) 把两个 like 包成一组：
                // 直接写 like(orderNo).or().like(customerName) 的话，后面 status 那个 eq
                // 会被 or 的优先级吃掉，变成 (orderNo like ? OR customerName like ? OR ...)，
                // 过滤条件整个失效。包一层就是 AND (orderNo like ? OR customerName like ?)。
                .and(StringUtils.hasText(query.keyword()), w -> w
                        .like(Order::getOrderNo, query.keyword())
                        .or()
                        .like(Order::getCustomerName, query.keyword()))
                .eq(query.status() != null, Order::getStatus, query.status())
                .orderBy(true, !query.descending(),
                        SORT_COLUMNS.getOrDefault(query.sortBy(), Order::getCreatedAt));

        Page<Order> result = orderMapper.selectPage(page, wrapper);

        return PageResponse.of(result.getRecords(), query.page(), query.size(),
                result.getTotal(), OrderResponse::from);
    }

    /**
     * 整体更新订单。
     * <p>
     * 传了 version 就带上乐观锁：MyBatis-Plus 会把 UPDATE 改写成
     * {@code SET version = version + 1 WHERE id = ? AND version = ?}，
     * 版本对不上就影响 0 行，这里翻译成 409。
     */
    public OrderResponse update(Long id, OrderUpdateRequest request) {
        Order existing = requireById(id);
        ensureModifiable(existing);

        Order patch = new Order();
        patch.setId(id);
        patch.setCustomerName(request.customerName());
        patch.setCustomerPhone(request.customerPhone());
        patch.setTotalAmount(request.totalAmount());
        // remark 配了 FieldStrategy.ALWAYS，所以这里传 null 也能把备注真正清空
        patch.setRemark(request.remark());
        patch.setItems(request.items());
        patch.setVersion(request.version());

        if (orderMapper.updateById(patch) == 0) {
            throw BusinessException.conflict("订单已被其他人修改，请刷新后重试");
        }
        return OrderResponse.from(requireById(id));
    }

    /** 状态流转，合法路径由 {@link OrderStatus#canTransferTo} 决定 */
    public OrderResponse changeStatus(Long id, OrderStatusRequest request) {
        Order existing = requireById(id);
        OrderStatus target = request.status();

        if (!existing.getStatus().canTransferTo(target)) {
            throw BusinessException.unprocessable("订单不允许从 %s 变更为 %s"
                    .formatted(existing.getStatus().getLabel(), target.getLabel()));
        }

        Order patch = new Order();
        patch.setId(id);
        patch.setStatus(target);
        patch.setVersion(request.version());

        if (orderMapper.updateById(patch) == 0) {
            throw BusinessException.conflict("订单已被其他人修改，请刷新后重试");
        }
        return OrderResponse.from(requireById(id));
    }

    /**
     * 删除订单。
     * <p>
     * 实体上有 {@code @TableLogic}，所以这里的 deleteById 实际执行的是
     * {@code UPDATE t_order SET deleted = 1 WHERE id = ? AND deleted = 0}——
     * 数据还在表里，只是被标记了。影响 0 行说明目标不存在（或已删过），抛 404。
     */
    public void delete(Long id) {
        if (orderMapper.deleteById(id) == 0) {
            throw new ResourceNotFoundException("订单", id);
        }
    }

    /** 回收站：已被逻辑删除的订单，走自定义 XML 查询 */
    public List<OrderResponse> findDeleted(int limit) {
        return orderMapper.selectDeleted(Math.clamp(limit, 1, 100)).stream()
                .map(OrderResponse::from)
                .toList();
    }

    /** 按状态聚合统计，走自定义 XML 查询 */
    public List<OrderStatusStat> stats() {
        return orderMapper.selectStatusStats();
    }

    private Order requireById(Long id) {
        Order order = orderMapper.selectById(id);
        if (order == null) {
            throw new ResourceNotFoundException("订单", id);
        }
        return order;
    }

    /** 终态订单不允许再改业务字段 */
    private void ensureModifiable(Order order) {
        if (order.getStatus().isTerminal()) {
            throw BusinessException.unprocessable(
                    "订单已处于终态 %s，不允许再修改".formatted(order.getStatus().getLabel()));
        }
    }

    private String nextOrderNo() {
        return "ORD" + LocalDate.now().format(ORDER_NO_DATE)
                + "%08d".formatted(RANDOM.nextInt(100_000_000));
    }
}
