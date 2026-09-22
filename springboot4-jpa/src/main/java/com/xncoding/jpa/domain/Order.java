package com.xncoding.jpa.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.SoftDelete;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订单聚合根。
 *
 * <h2>和上一篇（MyBatis-Plus）在建模上的差别</h2>
 * 上一篇把订单明细序列化成 JSON 塞进 {@code t_order} 的一个列里，读写都由 TypeHandler 负责，
 * 订单和明细从一开始就是一个整体。这一篇把明细拆成独立实体，用外键挂回来，
 * 于是"保存订单"变成了"保存一棵对象图"——插入订单时 Hibernate 会自己决定先插主表、
 * 回填主键、再插子表。这层编排不用写，是 {@code cascade} 带来的。
 *
 * <h2>三条元数据的归属</h2>
 * <ul>
 *   <li>{@code @SoftDelete} 是 <b>Hibernate</b> 的注解：删除变成 UPDATE，
 *       并且自动给所有 Hibernate 生成的查询加上过滤条件。</li>
 *   <li>{@code @Version} 是 <b>JPA</b> 的标准注解：更新时自动带版本条件，
 *       影响 0 行就抛 {@code ObjectOptimisticLockingFailureException}。</li>
 *   <li>{@code @CreatedDate} / {@code @LastModifiedDate} 是 <b>Spring Data</b> 的注解：
 *       靠 {@code AuditingEntityListener} 在持久化前后回调填充。</li>
 * </ul>
 * 三套机制分属三个技术栈，混在一张实体上，谁是负责的、坏了该去哪儿查，得分得清。
 */
@Entity
@Table(name = "t_order")
@SoftDelete(columnName = "deleted")
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 订单号。
     * <p>
     * {@code unique = true} 只是让 Hibernate 生成 DDL 时带上唯一约束；
     * 本篇用的是手写 schema.sql，真正的唯一索引在那边，
     * 但对不上时 {@code ddl-auto=validate} 不会报错——它只校验列，不校验索引。
     */
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 20)
    private String customerPhone;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /**
     * {@code EnumType.STRING} 必须显式写。
     * <p>
     * JPA 的默认是 {@code ORDINAL}，也就是往库里写 {@code 0}、{@code 1}。
     * 一旦在枚举中间插入新值，历史数据的含义会整体错位，而且不会有任何报错。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(length = 255)
    private String remark;

    /**
     * 乐观锁版本号。
     * <p>
     * 不需要自己维护：Hibernate 在 UPDATE 时会自动带上 {@code where version = ?} 并把值加一。
     * 影响行数为 0 说明期间被别人改过，直接抛异常回滚。
     * <p>
     * 这里没有 {@code @Column(insertable = false)} 之类的花招，插入时由数据库默认值 0 兜底；
     * 想让新建订单的响应里就带上 version，靠的是插入后 Hibernate 会回读一次（IDENTITY 主键策略下本来就要回读）。
     */
    @Version
    @Column(nullable = false)
    private Integer version;

    /** 插入时由审计监听器填，之后不再允许改 */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 每次更新都由审计监听器重写 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 订单明细，一对多。
     *
     * <h3>mappedBy 写在这里，但关系的维护方在 OrderItem 那一侧</h3>
     * {@code mappedBy} 的意思就是"外键不在我这儿，去 {@link OrderItem#getOrder()} 找"。
     * 所以这个集合是<b>只读</b>的：光往集合里加元素、不设置子实体的 {@code order}，
     * 外键列会是 null，插入直接失败。{@link #addItem} 就是用来兜住这件事的。
     *
     * <h3>cascade 和 orphanRemoval</h3>
     * {@code CascadeType.ALL} 让保存/删除订单时连带处理明细；
     * {@code orphanRemoval} 则让"从集合里移除"等于"删除该行"。
     * 两者都加上，订单和明细才是真正的一个整体。
     *
     * <h3>LAZY 是默认值，这里写出来是为了明确</h3>
     * 一对多默认就是懒加载，集合在第一次访问时才发 SQL。配合
     * {@code spring.jpa.open-in-view=false}，这个访问必须发生在事务内，
     * 否则拿到的是一堆空集合，而且不报错。
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // JPA 要求的无参构造
    }

    public Order(String orderNo, String customerName, String customerPhone,
                 BigDecimal totalAmount, String remark) {
        this.orderNo = orderNo;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.totalAmount = totalAmount;
        this.remark = remark;
        this.status = OrderStatus.CREATED;
    }

    /** 加一条明细，同时把反向引用补上，避免外键为 null */
    public void addItem(OrderItem item) {
        item.setOrder(this);
        this.items.add(item);
    }

    /**
     * 整体替换明细。
     * <p>
     * 不能写成 {@code this.items = newList}：Hibernate 跟踪的是集合实例本身，
     * 直接换掉引用会让它以为旧集合还在，orphanRemoval 也感知不到被移除的元素，
     * 结果就是旧的明细行永远留在表里。必须就地增删。
     */
    public void replaceItems(List<OrderItem> newItems) {
        this.items.clear();
        newItems.forEach(this::addItem);
    }

    /** 对外只给不可变视图，防止调用方绕过 addItem 直接改集合 */
    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getVersion() {
        return version;
    }

    /**
     * 写入版本号。
     * <p>
     * 这个 setter <b>不是</b>用来做乐观锁校验的。Hibernate 的 {@code @Version} 在
     * 生成 UPDATE 时，条件里用的是持久化上下文里的快照版本，不是这里刚写进去的值，
     * 所以手动塞一个过期版本号并不会让更新失败，反而会把这个过期值直接写进库里。
     * <p>
     * 真正需要"如果被人改过就拒绝"的场景，必须由服务层自己拿客户端传来的版本号
     * 和当前版本比一次（见 {@code OrderService.checkVersion}）。
     * 这个方法保留成 public，只是为了让实体在需要时能设置版本值。
     */
    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
