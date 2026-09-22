package com.xncoding.restful.service;

import com.xncoding.restful.domain.Order;
import com.xncoding.restful.domain.OrderPageQuery;
import com.xncoding.restful.domain.OrderStatus;
import com.xncoding.restful.domain.PageSlice;
import com.xncoding.restful.dto.OrderCreateRequest;
import com.xncoding.restful.dto.OrderUpdateRequest;
import com.xncoding.restful.exception.BusinessException;
import com.xncoding.restful.exception.ResourceNotFoundException;
import com.xncoding.restful.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单业务层。
 */
@Service
public class OrderService {

    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final int ORDER_NO_MAX_RETRY = 3;

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 创建订单，订单号由服务端生成，客户端无权指定。
     */
    public Order create(OrderCreateRequest request) {
        Instant now = Instant.now();
        Order order = new Order(null, generateOrderNo(), request.customerName(),
                request.customerPhone(), request.totalAmount(), OrderStatus.CREATED,
                request.remark(), now, now);
        return orderRepository.save(order);
    }

    /**
     * 按主键查询，不存在时抛 404。
     */
    public Order getById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("订单", id));
    }

    /**
     * 整体更新订单业务字段。订单号与状态不受本接口影响。
     */
    public Order update(Long id, OrderUpdateRequest request) {
        Order existing = getById(id);
        ensureModifiable(existing);
        Order updated = existing.withBusinessFields(request.customerName(), request.customerPhone(),
                request.totalAmount(), request.remark(), Instant.now());
        return orderRepository.save(updated);
    }

    /**
     * 状态流转，非法流转返回 422。
     */
    public Order transferStatus(Long id, OrderStatus target) {
        Order existing = getById(id);
        if (existing.status() == target) {
            throw BusinessException.unprocessable(
                    "订单当前已是 %s 状态".formatted(target.getLabel()));
        }
        if (!existing.status().canTransferTo(target)) {
            throw BusinessException.unprocessable(
                    "订单不允许从 %s 变更为 %s".formatted(existing.status().getLabel(), target.getLabel()));
        }
        return orderRepository.save(existing.withStatus(target, Instant.now()));
    }

    /**
     * 删除订单。目标不存在时抛 404，而不是静默成功——
     * 让"删掉了"与"本来就没有"对客户端可区分，同时保证错误响应格式与其它接口一致。
     */
    public void delete(Long id) {
        if (!orderRepository.deleteById(id)) {
            throw new ResourceNotFoundException("订单", id);
        }
    }

    /**
     * 分页查询。
     */
    public PageSlice<Order> page(OrderPageQuery query) {
        return orderRepository.findPage(query);
    }

    /**
     * 终态订单不允许再修改，避免"已取消的订单又改金额"这类脏数据。
     */
    private void ensureModifiable(Order order) {
        if (order.status() == OrderStatus.COMPLETED || order.status() == OrderStatus.CANCELLED) {
            throw BusinessException.unprocessable(
                    "%s 状态的订单不允许修改".formatted(order.status().getLabel()));
        }
    }

    /**
     * 生成订单号：ORD + 日期 + 8 位随机数，冲突则重试。
     */
    private String generateOrderNo() {
        for (int i = 0; i < ORDER_NO_MAX_RETRY; i++) {
            String candidate = "ORD%s%08d".formatted(LocalDate.now().format(ORDER_NO_DATE),
                    ThreadLocalRandom.current().nextInt(100_000_000));
            if (!orderRepository.existsByOrderNo(candidate)) {
                return candidate;
            }
        }
        throw BusinessException.conflict("订单号生成冲突，请稍后重试");
    }
}
