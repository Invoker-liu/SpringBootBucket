package com.xncoding.multisource.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xncoding.multisource.domain.Order;
import com.xncoding.multisource.dto.OrderDetail;
import com.xncoding.multisource.exception.ResourceNotFoundException;
import com.xncoding.multisource.mapper.pos.OrderMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 方案 A 的演示服务：静态绑定交易库。
 *
 * <p>这个类里没有任何「切换」的代码，连注解都没有 —— 数据源固定在
 * {@code posSqlSessionFactory} 上，也就是固定在交易库上。
 * 需要查运营库就得换 {@code BizProductService}，两个库的代码在编译期就是分开的。
 * 这就是静态方案最大的特点，也是它最大的限制。
 *
 * <p>事务管理器全部显式写了名字。容器里有三个
 * （{@code posTransactionManager} / {@code bizTransactionManager} / {@code dynamicTransactionManager}），
 * 谁都没标 {@code @Primary}，写裸的 {@code @Transactional} 会直接抛
 * {@code NoUniqueBeanDefinitionException}。多数据源下「这个事务管的是哪个库」
 * 本来就该是一个被显式回答的问题，逼你写出来是好事。
 */
@Service
public class PosOrderService {

    private final OrderMapper orderMapper;

    public PosOrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public OrderDetail detail(Long id) {
        OrderDetail detail = orderMapper.selectOrderDetail(id);
        if (detail == null) {
            throw new ResourceNotFoundException("订单", id);
        }
        return detail;
    }

    public List<Order> list() {
        return orderMapper.selectList(Wrappers.<Order>lambdaQuery().orderByAsc(Order::getId));
    }

    /** 订单总数。事务演示要靠它前后对比来判断有没有回滚。 */
    public int count() {
        return orderMapper.countAll();
    }

    /** 交易库这个 mapper 实际连到的库名，用来核对静态绑定有没有配错。 */
    public String currentDatabase() {
        return orderMapper.currentDatabase();
    }

    // ------------------------------------------------------------------
    // 事务演示：同样的代码，只换事务管理器，结果完全不同
    // ------------------------------------------------------------------

    /**
     * 正确写法：事务管理器绑的就是这次要写的库。
     *
     * <p>插入之后抛异常，Spring 回滚，交易库里不会留下任何东西。
     */
    @Transactional("posTransactionManager")
    public void insertThenFailCorrectly(String orderNo) {
        orderMapper.insert(newOrder(orderNo));
        throw new IllegalStateException("故意失败，观察交易库这一笔有没有回滚");
    }

    /**
     * 错误写法：往交易库写，事务却开在运营库上。
     *
     * <p>这段代码<b>不会有任何报错</b>，只有两件事值得注意：
     * <ol>
     *   <li>{@code @Transactional("bizTransactionManager")} 让 Spring 去运营库拿了一条连接、
     *       开了一个事务，然后把它绑在当前线程上；
     *   <li>{@code orderMapper.insert(...)} 用的是交易库自己的连接，
     *       而那条连接不在任何事务里（Hikari 默认自动提交），
     *       所以 insert 在方法返回前就已经落库了。
     * </ol>
     *
     * <p>紧接着抛异常，Spring 老老实实把运营库上那个事务回滚了 —— 但那个事务里
     * 本来就没有任何写入。日志上看「事务回滚成功」，交易库的数据一条不少地留在那里。
     * <b>这是多数据源里最隐蔽的一类 bug，因为它连一条 WARN 都不会打。</b>
     */
    @Transactional("bizTransactionManager")
    public void insertThenFailWithWrongManager(String orderNo) {
        orderMapper.insert(newOrder(orderNo));
        throw new IllegalStateException("故意失败，观察交易库这一笔有没有回滚");
    }

    private Order newOrder(String orderNo) {
        Order order = new Order();
        order.setOrderNo(orderNo);
        order.setCustomerName("多数据源演示");
        order.setTotalAmount(new BigDecimal("199.00"));
        order.setStatus("CREATED");
        order.setCreatedAt(LocalDateTime.now());
        order.setVersion(0);
        return order;
    }
}
