package com.xncoding.mongo.domain;

import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 订单明细。
 *
 * <h2>它没有 {@code @Document}</h2>
 * 因为它不单独存成一个集合，而是<b>内嵌</b>在 {@link Order} 文档的 {@code items} 数组里。
 * 这是三种存储在同一份数据模型上的三副面孔：
 * <ul>
 *   <li>上一篇（MyBatis-Plus）：整份明细序列化成 JSON 字符串塞进一个列，读写靠 TypeHandler；</li>
 *   <li>上上篇（JPA）：拆成独立的 {@code t_order_item} 表，用外键挂回来，保存订单等于保存一棵对象图；</li>
 *   <li>这一篇（MongoDB）：<b>直接就是文档里的一个数组</b>，没有表、没有外键、没有 JOIN，
 *       查一个订单带上明细永远只有一次读取。</li>
 * </ul>
 * 内嵌的代价也要说清楚：单个文档有 16MB 上限，而且明细不是独立实体，
 * 想"按商品维度跨订单查询"就得靠数组元素匹配（本篇的 {@code findByItemsProductName} 就是），
 * 规模上去之后要么改建模、要么上 {@code $lookup}。
 *
 * @param price    商品单价
 * @param quantity 数量
 */
public class OrderItem {

    @Field("product_name")
    private String productName;

    private BigDecimal price;

    private int quantity;

    protected OrderItem() {
        // Spring Data 要求的无参构造
    }

    public OrderItem(String productName, BigDecimal price, int quantity) {
        this.productName = productName;
        this.price = price;
        this.quantity = quantity;
    }

    /**
     * 小计，由单价乘数量算出来，<b>不落库</b>。
     * <p>
     * {@code @Transient} 这一行不能省。Spring Data 的属性发现是按 getter 来的：
     * 只要有一个 {@code getAmount()}，它就会认为 {@code amount} 也是一个要持久化的属性，
     * 于是每次插入都会在文档里多写一个 {@code amount} 字段，而且这个字段永远不会被更新
     * （因为它是算出来的，没有 setter 也不会被回写），最后变成一个对不上的幽灵字段。
     * <p>
     * 注意别导错包：要的是 {@code org.springframework.data.annotation.Transient}。
     * 还有一个名字一模一样的 {@code jakarta.persistence.Transient}（JPA 那个），
     * 以及 {@code java.beans.Transient}，导错了都不报错，只是不生效。
     */
    @Transient
    public BigDecimal getAmount() {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
