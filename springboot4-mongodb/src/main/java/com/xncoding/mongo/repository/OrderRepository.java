package com.xncoding.mongo.repository;

import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储。
 *
 * <h2>{@code MongoRepository<T, ID>} 的第二个类型参数是主键类型</h2>
 * 这里必须是 {@code String}（对应文档的 {@code _id}），不是 {@code Long}。
 * 写错了不会编译报错——{@code MongoRepository} 是泛型擦除的——
 * 而是在启动时抛 {@code InvalidDataAccessApiUsageException}，
 * 说"找不到能把 ObjectId 转成 Long 的转换器"。看到这条报错就回来改这里。
 *
 * <h2>派生查询：方法名即查询</h2>
 * 这一层和上一篇（JPA）长得几乎一样，但底下完全不是一回事：
 * <ul>
 *   <li>JPA 把方法名解析成 <b>JPQL</b>，再翻译成 SQL；</li>
 *   <li>MongoDB 把方法名解析成一个 {@code Query} 对象（{@code Criteria} 树），
 *       直接发给驱动，没有中间那一层查询语言。</li>
 * </ul>
 * 行为上有一处必须注意的差别：方法名里的字段用 Java 属性名
 * （{@code createdAt}、{@code orderNo}），框架会按 {@code @Field} 的映射
 * 自动转成存储名（{@code created_at}、{@code order_no}）。
 * <p>
 * <b>至于下面手写的 {@code @Query} / {@code @Aggregation} 里的 JSON 会不会被翻译，
 * 我先按"裸字符串原样下发"写，实测发现是错的。</b>
 * 两种情况都做了对照方法，见 {@link #findByItemProductNameWithPropertyName} 与
 * {@link #findByStatusWithFields}。结论：会翻译，而且 {@code @Aggregation}
 * 连输出别名都翻译，那个坑见 {@link com.xncoding.mongo.dto.OrderStatusStat}。
 */
public interface OrderRepository extends MongoRepository<Order, String> {

    /**
     * 按订单号精确查（派生查询）。
     * <p>
     * 属性名写 {@code orderNo}，框架翻译成文档里的 {@code order_no}。
     * 走的是 {@code uk_order_no} 唯一索引——前提是那个索引真的建出来了，
     * 也就是 {@code auto-index-creation} 必须打开。
     */
    Optional<Order> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    long countByStatus(OrderStatus status);

    /**
     * 按状态查，创建时间倒序。
     * <p>
     * {@code OrderByCreatedAtDesc} 里的 {@code CreatedAt} 同样是 Java 属性名，
     * 会被翻译成 {@code created_at}。写 {@code OrderByCreated_atDesc} 会直接启动失败
     * （属性名解析不过去）。
     */
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    /**
     * 按状态分页。
     * <p>
     * {@code Pageable} 里带的排序同样走属性名。注意分页查询会额外发一条 count 命令，
     * 这是返回 {@code Page}（而不是 {@code Slice}）的固定代价。
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * 金额不低于指定值（派生查询 + 比较运算符）。
     * <p>
     * 属性名 {@code TotalAmount} → 存储名 {@code total_amount}。
     */
    List<Order> findByTotalAmountGreaterThanEqual(BigDecimal minAmount);

    /**
     * <b>内嵌数组的匹配查询，用 {@code @Query} 手写。</b>
     * <p>
     * 想表达的是"哪些订单里含某个商品"。MongoDB 的写法很自然——
     * 用点号直接钻进数组：{@code 'items.product_name': '机械键盘'}。
     * 数组元素的匹配不需要 {@code $elemMatch}，因为这里只有一个条件；
     * 一旦要对<b>同一个元素</b>同时匹配多个字段（比如"商品名是 A 且数量大于 2"），
     * 就必须用 {@code $elemMatch}，否则会变成"某个元素名字是 A"且"某个元素数量大于 2"，
     * 两个条件可以落在不同元素上。
     * <p>
     * 这里的 {@code ?0} 是占位符，运行时会替换成方法参数。
     * <p>
     * <b>{@code items.product_name} 里的 {@code product_name} 我一开始以为是必须的，
     * 实测下来写属性名 {@code items.productName} 也能查到。</b>
     * 也就是说 {@code @Query} 的 JSON <b>也会</b>做属性名映射，
     * 并不是"裸字符串原样下发"。这一点很容易被想当然搞反，
     * 所以下面特意留了个对照方法 {@link #findByItemProductNameWithPropertyName}，
     * 让测试把两种写法都跑一遍。
     * <p>
     * 这个查询<b>用不上任何索引</b>：{@code uk_order_no}、{@code idx_status}、
     * {@code idx_status_created_at} 都帮不上忙，全集合扫描。
     * 要给内嵌字段建索引就是 {@code db.orders.createIndex({'items.product_name': 1})}，
     * 多键索引，一条文档里的每个数组元素都会进索引。
     */
    @Query("{ 'items.product_name': ?0 }")
    List<Order> findByItemProductName(String productName);

    /**
     * 和 {@link #findByItemProductName} 等价的对照写法：字段名用 Java 属性名。
     * <p>
     * 它存在的意义只有一个——把"手写查询会不会被翻译"这件事从推测变成实测。
     * 切片测试里断言两个方法返回完全相同的结果。
     * <p>
     * 之所以要专门验一次，是因为这个结论很容易反着记：直觉上"手写 JSON 就是原样下发"，
     * 而实际上 Spring Data 会在下发前把能对上属性的路径换成存储名。
     * 接着往下想就会踩到 {@code @Aggregation} 那个更隐蔽的坑（连输出别名都被改），
     * 见 {@link com.xncoding.mongo.dto.OrderStatusStat} 的类注释。
     */
    @Query("{ 'items.productName': ?0 }")
    List<Order> findByItemProductNameWithPropertyName(String productName);

    /**
     * 只取部分字段的查询，用 {@code @Query} 的 {@code fields} 做投影。
     * <p>
     * {@code fields = "{ 'order_no': 1, 'total_amount': 1, 'status': 1 }"} 等价于
     * {@code db.orders.find({...}, {order_no: 1, total_amount: 1, status: 1})}，
     * 只把这几列拉回来（{@code _id} 默认总是带上）。
     * <p>
     * 代价是返回的 {@code Order} 对象里其余字段都是 {@code null}——
     * 它不是一个"字段少一些的实体"，而是"字段没查所以为空"。
     * 一旦有人拿这个结果去做 {@code save()}，那些 null 会把库里真实的值覆盖掉。
     * 所以投影结果要么转成 DTO 立刻用掉，要么只读不写。下面那个
     * {@link OrderSummaryRepository} 的接口投影是更安全的做法。
     */
    @Query(value = "{ 'status': ?0 }",
            fields = "{ 'order_no': 1, 'customer_name': 1, 'total_amount': 1, 'status': 1 }")
    List<Order> findByStatusWithFields(OrderStatus status);

    /**
     * 按状态聚合统计，用 {@code @Aggregation} 声明管道。
     * <p>
     * 这里的两处字段名都是<b>存储名</b>，不是 Java 属性名：
     * <ul>
     *   <li>{@code $group} 的 group key {@code status} —— 恰好和属性名同形，容易误以为"这里也翻译"；</li>
     *   <li>{@code sum('total_amount')} —— 这个就明显了，属性名是 {@code totalAmount}，
     *       写属性名会得到全 0（{@code $sum} 对不存在的字段求和返回 0，不报错）。</li>
     * </ul>
     * 管道是原样下发给 MongoDB 的，Spring Data 一个字段都不会替我们翻译。
     * <p>
     * {@code pipeline} 里每个字符串是一个阶段，等价于：
     * <pre>
     * db.orders.aggregate([
     *   { $group: { _id: "$status", count: { $sum: 1 }, totalAmount: { $sum: "$total_amount" } } },
     *   { $sort: { _id: 1 } }
     * ])
     * </pre>
     * 结果里 group key 一定叫 {@code _id}，所以返回类型
     * {@link com.xncoding.mongo.dto.OrderStatusStat} 上要用 {@code @Field("_id")} 接住它。
     */
    @Aggregation(pipeline = {
            "{ $group: { _id: '$status', count: { $sum: 1 }, totalAmount: { $sum: '$total_amount' } } }",
            "{ $sort: { _id: 1 } }"
    })
    List<com.xncoding.mongo.dto.OrderStatusStat> statByStatus();
}
