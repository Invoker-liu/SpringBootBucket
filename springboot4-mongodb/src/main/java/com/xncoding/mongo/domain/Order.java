package com.xncoding.mongo.domain;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 订单文档。
 *
 * <h2>{@code @Document} 对应 JPA 的 {@code @Entity} + {@code @Table}</h2>
 * {@code collection = "orders"} 指定集合名。不写的话按类名首字母小写变成 {@code order}——
 * 而 {@code order} 在 MongoDB 里不是保留字，能建，但读起来容易和"排序"混淆，
 * 而且哪天类名改了集合名就跟着变，数据"消失"得莫名其妙。显式写死。
 *
 * <h2>整套概念对照（这是本篇最该看懂的一张表）</h2>
 * <table border="1">
 *   <tr><th>关系型</th><th>MongoDB</th><th>本类里对应</th></tr>
 *   <tr><td>表 table</td><td>集合 collection</td><td>{@code @Document(collection = "orders")}</td></tr>
 *   <tr><td>行 row</td><td>文档 document</td><td>一个 {@code Order} 对象</td></tr>
 *   <tr><td>列 column</td><td>字段 field</td><td>下面这些属性</td></tr>
 *   <tr><td>主键 id（自增 Long）</td><td>{@code _id}（ObjectId）</td><td>{@code @Id String id}</td></tr>
 *   <tr><td>外键 + 子表</td><td>内嵌数组</td><td>{@code List<OrderItem> items}</td></tr>
 *   <tr><td>索引 index</td><td>索引 index</td><td>{@code @Indexed} / {@code @CompoundIndex}</td></tr>
 * </table>
 *
 * <h2>主键类型从 Long 变成了 String</h2>
 * MongoDB 的 {@code _id} 默认是 {@link org.bson.types.ObjectId}——12 字节，包含时间戳、
 * 机器标识、进程号、自增计数器。它不是自增整数，所以没有"下一条记录 id 更大"这种性质，
 * 也不能靠 id 排序来推断插入顺序（要按时间排就排 {@code created_at}）。
 * <p>
 * 声明成 {@code String} 而不是 {@code ObjectId}，是为了让接口的 {@code /api/orders/{id}}
 * 和前三篇长得一样——{@code ObjectId} 在 URL 里要额外处理序列化，收益不大。
 * Spring Data 会自动在两者之间转换，不需要写转换器。
 */
@Document(collection = "orders")
/*
 * 复合索引：状态 + 创建时间倒序，正好覆盖"按状态筛、按时间倒排"这个最高频的查询。
 *
 * 注意 def 里写的是 'created_at' 而不是 'createdAt' ——
 * 这个字符串是**原样发给 MongoDB 的**，MongoDB 只认文档里真实存在的字段名，
 * 而本类把 createdAt 映射到了 created_at（见下面的 @Field）。
 * 写错成 'createdAt' 不会报错，索引照样建出来，只是永远用不上，
 * 唯一能发现的途径是 explain() 里看到它没进 winningPlan。
 *
 * name 显式写死：不写的话 Spring Data 会按字段名生成一个又长又难认的名字，
 * 而且改一下字段顺序名字就变了，运维那边对不上账。
 */
@CompoundIndex(name = "idx_status_created_at", def = "{'status': 1, 'created_at': -1}")
public class Order {

    @Id
    private String id;

    /**
     * 订单号，唯一索引。
     * <p>
     * <b>{@code @Indexed(unique = true)} 默认什么都不会发生</b>——它只是一份"索引定义"，
     * 真正去建索引的是 {@code spring.data.mongodb.auto-index-creation}，
     * 而这个开关的默认值是 {@code false}。所以下面这段配置是本篇能跑通的前提：
     * <pre>
     * spring:
     *   data:
     *     mongodb:
     *       auto-index-creation: true
     * </pre>
     * 忘了写会怎样：应用正常启动、文档正常写入、{@code findByOrderNo} 正常返回，
     * 一切看起来都好，只有唯一约束是不存在的——重复订单号能塞进去。
     * 去 {@code db.orders.getIndexes()} 看了一眼才发现除了 {@code _id} 什么都没有。
     * 本篇第五节会用真实运行把这个差别打出来。
     * <p>
     * {@code @Field("order_no")}：Java 属性叫 {@code orderNo}，文档里存成 {@code order_no}。
     * 之所以刻意改成下划线风格，是为了让同一份数据模型在 MySQL、MongoDB 里的字段名对得上，
     * 对照前三篇的 SQL 不用在脑子里做映射。但改名的代价也很实在：
     * <b>所有手写的查询、聚合、索引定义，用的都必须是改完之后的存储名</b>，
     * 只有派生查询的方法名用 Java 属性名。两者混在同一个类里，是本篇最容易写错的地方。
     */
    @Indexed(unique = true, name = "uk_order_no")
    @Field("order_no")
    private String orderNo;

    @Field("customer_name")
    private String customerName;

    @Field("customer_phone")
    private String customerPhone;

    @Field("total_amount")
    private BigDecimal totalAmount;

    /** 建单索引：按状态筛是最常见的入口 */
    @Indexed(name = "idx_status")
    private OrderStatus status;

    private String remark;

    /**
     * 乐观锁版本号。
     * <p>
     * <b>Spring Data MongoDB 的 {@code @Version} 和 JPA 的行为不一样，这块必须说清。</b>
     * JPA 是"Hibernate 在 UPDATE 语句里自动带上版本条件"，你写 {@code save()} 就完事；
     * MongoDB 这边的版本控制实现在 Spring Data 的仓储/模板层：保存时用
     * {@code _id + version} 做条件去替换整份文档，匹配不到就抛
     * {@link org.springframework.dao.OptimisticLockingFailureException}。
     * <p>
     * 后果有两个，都是坑：
     * <ol>
     *   <li><b>只要绕开 {@code save()}，版本就管不住。</b>
     *       {@code MongoTemplate.updateFirst(query, update, ...)} 是直接发 {@code $set}，
     *       版本号原地不动、也不会校验。本篇的 {@code PUT} 走 {@code save()}（受保护），
     *       而 {@code PATCH /{id}/status} 走 {@code findAndModify}（不受保护，靠
     *       {@code where status = 当前状态} 这个条件来保证并发安全）。两种做法都对，
     *       但理由完全不同，不能互相替换着理解。</li>
     *   <li><b>客户端带来的版本号同样要自己比一次。</b>和 JPA 那篇一模一样的道理：
     *       {@code @Version} 拿的是"同一个上下文里读出来的"那份快照，
     *       而客户端传来的版本号隔着一次 HTTP 往返，框架不知道这回事。</li>
     * </ol>
     */
    @Version
    private Long version;

    /** 插入时由审计回调填，之后不再允许改 */
    @CreatedDate
    @Field("created_at")
    private LocalDateTime createdAt;

    /** 每次保存都由审计回调重写 */
    @LastModifiedDate
    @Field("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 订单明细，内嵌文档数组。
     * <p>
     * 不需要 {@code cascade}、不需要 {@code orphanRemoval}、不需要管外键谁维护——
     * 整个数组就是本文档的一部分，保存订单时它跟着一起写下去，删掉就从数组里拿掉。
     * JPA 那篇花了一整节讲"集合实例不能直接换引用"，这里换不换引用都无所谓，
     * 因为根本不存在"Hibernate 跟踪集合实例"这件事。
     * <p>
     * 仍然是给外部不可变视图，避免调用方绕过 {@link #addItem} 直接改集合。
     */
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {
        // Spring Data 要求的无参构造
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

    public void addItem(OrderItem item) {
        this.items.add(item);
    }

    /** 整体替换明细：这里直接换掉列表就行，没有 JPA 那层"集合实例被跟踪"的顾虑 */
    public void replaceItems(List<OrderItem> newItems) {
        this.items = new ArrayList<>(newItems);
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public String getId() {
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

    public Long getVersion() {
        return version;
    }

    /**
     * 和 JPA 那篇一样，这个 setter <b>不</b>用来做乐观锁校验。
     * <p>
     * Spring Data 在 {@code save()} 时用的是"从库里读出来的那份文档"的版本号去构造条件，
     * 手动塞一个过期版本号不会让保存失败，反而会把这个值写进库里，
     * 让后续所有基于版本的判断全部错位。要"被人改过就拒绝"，得在服务层自己比一次。
     */
    public void setVersion(Long version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
