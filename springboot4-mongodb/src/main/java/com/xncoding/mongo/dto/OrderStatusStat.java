package com.xncoding.mongo.dto;

import com.xncoding.mongo.domain.OrderStatus;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;

/**
 * 按状态的聚合统计结果。
 *
 * <h2>为什么这个类里有两个 {@code @Field}，而且看起来都不太对劲</h2>
 * MongoDB 的 {@code $group} 阶段输出的文档长这样：
 * <pre>
 * { "_id": "CREATED", "count": 3, "total_amount": {"$numberDecimal": "1289.50"} }
 * </pre>
 *
 * <b>第一个是 {@code _id}。</b>group key 一定叫 {@code _id}——这是 MongoDB 的固定约定，
 * 改不了。而 {@code _id} 在本类的映射里默认指向"主键"，会被当成 ObjectId 处理，
 * 所以必须显式把它映射到 {@code status} 上。
 * <p>
 * <b>第二个是 {@code total_amount}，这个是真的反直觉，也是本篇踩到的一个真坑。</b>
 * 直觉上聚合结果里的键名就是管道里 {@code $group} 起的别名，写成 {@code totalAmount}
 * 就该拿到 {@code totalAmount}。但实际不是这样：<b>{@code @Aggregation} 的管道字符串
 * 会先按实体映射做一次属性名解析——能对上实体属性的就换成存储名，对不上的原样保留。</b>
 * <p>
 * 于是 {@code totalAmount: {$sum: '$total_amount'}} 里的别名 {@code totalAmount}
 * 恰好是 {@code Order} 的属性名，被改写成 {@code total_amount}；
 * 而同一个管道里的 {@code count} 因为 {@code Order} 里没有这个属性，原样不变。
 * 打开驱动的命令日志能直接看到这个改写：
 * <pre>
 * Command: {"aggregate": "orders", "pipeline": [
 *   {"$group": {"_id": "$status", "count": {"$sum": 1},
 *               "total_amount": {"$sum": "$total_amount"}}},
 *   {"$sort": {"_id": 1}}]}
 * </pre>
 * 我写的明明是 {@code totalAmount}，发出去的是 {@code total_amount}。
 * 结果文档里当然只有 {@code total_amount}，而这个类的字段名是 {@code totalAmount}，
 * 映射不上，字段就是 {@code null}——不报错，接口返回里少一个字段而已。
 * <p>
 * 修法就是把这一侧的存储名对齐到改写后的结果。之所以不用"改别名"来绕开，
 * 是因为另一个实现（{@code OrderMongoDao} 用 fluent API 搭的同一条管道）
 * <b>不会</b>被改写：那边 {@code .as("totalAmount")} 就真的是 {@code totalAmount}。
 * 同一个别名，两条路径落到文档上的键名不一样，这里只能按"两条路径都能对上"的方式声明。
 * <p>
 * 所以正确的理解方式是：<b>别把聚合结果的键名当成"我起的别名"，
 * 它可能已经被实体映射动过手脚。写完之后打开命令日志看一眼发出去的报文，是最省事的做法。</b>
 *
 * @param count       该状态下的订单数
 * @param totalAmount 该状态下的金额合计
 */
public class OrderStatusStat {

    @Field("_id")
    private OrderStatus status;

    /** 这个别名没有对应任何实体属性，所以没被改写，原样就是 {@code count} */
    private long count;

    /**
     * 金额合计。
     * <p>
     * 聚合里的 {@code $sum} 对空集返回 0（不是 null），所以这里不会出现 NPE——
     * 这一点和上一篇不同：JPA 的 {@code sum()} 在空集上返回 null，
     * 那边必须靠 JPQL 的 {@code coalesce} 兜住。
     * <p>
     * 还有一个类型细节：{@code BigDecimal} 写进 MongoDB 是 Decimal128，
     * {@code $sum} 出来的还是 Decimal128（报文里是 {@code {"$numberDecimal": "599.00"}}），
     * 读回来由 Spring Data 转换成 {@code BigDecimal}。
     * 换成 {@code double} 接也能跑，但会丢精度——金额不要这么干。
     */
    @Field("total_amount")
    private BigDecimal totalAmount;

    public OrderStatusStat() {
        // 反序列化用的无参构造
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    /** 展示用的中文名，由状态名现算，文档里没有这个字段 */
    @Transient
    public String getStatusLabel() {
        return status == null ? null : status.getLabel();
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }
}
