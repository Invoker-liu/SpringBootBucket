package com.xncoding.jpa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.SoftDelete;

import java.math.BigDecimal;

/**
 * 订单明细，{@link Order} 的子实体。
 * <p>
 * 和上一篇最大的不同在这里：明细不再序列化成 JSON 塞进一个列，而是真正落到
 * {@code t_order_item} 表，靠外键挂回订单。好处是能用 SQL 直接按商品维度查询统计，
 * 坏处是每次读订单都要多一次关联查询，也就是 N+1 的由来。
 * <p>
 * <b>不要把 {@code order} 写进 {@code toString()} / {@code equals()}</b>：
 * 双向关联下这类自动生成代码很容易打成死循环，更隐蔽的是它们会顺带触发懒加载，
 * 把查询带进日志输出这种本来不该碰数据库的地方。
 */
@Entity
@Table(name = "t_order_item")
@SoftDelete(columnName = "deleted")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 指回订单。
     * <p>
     * {@code fetch = LAZY}：多对一默认是 EAGER，这里显式关掉。加载一条明细就顺带把
     * 订单整行读出来，在"查某订单的明细"这类场景下是纯浪费。
     * <p>
     * 这是 {@code mappedBy} 的另一端，也就是关系的维护方；{@link Order#getItems()}
     * 那一侧只读不写。外键列由这里决定，所以在订单上改集合并不会自动同步外键。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_name", nullable = false, length = 128)
    private String productName;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer quantity;

    protected OrderItem() {
        // JPA 要求无参构造，声明成 protected 是为了让业务代码只能走下面那个有参构造器
    }

    public OrderItem(String productName, BigDecimal price, Integer quantity) {
        this.productName = productName;
        this.price = price;
        this.quantity = quantity;
    }

    /** 小计，不落库，只用于响应体 */
    public BigDecimal getAmount() {
        return price == null || quantity == null ? null : price.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    /** 只允许 {@link Order} 调用，用来维护双向关联的另一半 */
    void setOrder(Order order) {
        this.order = order;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
