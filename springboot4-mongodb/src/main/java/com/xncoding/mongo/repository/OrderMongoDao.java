package com.xncoding.mongo.repository;

import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderItem;
import com.xncoding.mongo.domain.OrderStatus;
import com.xncoding.mongo.dto.OrderStatusStat;
import com.xncoding.mongo.dto.ProductSales;
import org.bson.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 用 {@link MongoTemplate} 手写数据访问。
 *
 * <h2>为什么仓储接口之外还要有这么一层</h2>
 * {@code MongoRepository} 能覆盖"按字段查、按字段排、分页"这类标准动作，
 * 但它有两个覆盖不到的地方：
 * <ul>
 *   <li><b>原子更新。</b>仓储的 {@code save()} 是"整份文档替换"，
 *       并发下必须靠 {@code @Version} 兜；而 {@code $set} / {@code $inc} / {@code $push}
 *       这些是服务端原子操作，不需要读出来再写回去。要用它们就得下到模板层。</li>
 *   <li><b>聚合管道。</b>仓储上能用 {@code @Aggregation}，但管道一旦需要按条件拼接
 *       （比如"关键字非空时才加这个阶段"），注解就写不下去了，只能用 Java 代码搭。</li>
 * </ul>
 * 这一层不是"更底层的替代品"，是补充。仓储管标准 CRUD，模板管原子操作与聚合。
 *
 * <h2>字段名到底会不会被翻译？这一节是实测结论，不是推测</h2>
 * 我一开始以为"模板层写裸字符串，所以要自己写存储名"，实测下来是错的，
 * 分三种情况：
 * <ol>
 *   <li><b>类型化 API 会翻译。</b>{@code Criteria.where("items.productName")}
 *       会被解析成 {@code items.product_name} 再去匹配；{@code Sort} 里的属性名同理。
 *       所以这一层其实可以直接写 Java 属性名。</li>
 *   <li><b>{@code @Query} 的 JSON 字符串也会翻译。</b>见下面反例方法的说明。</li>
 *   <li><b>{@code @Aggregation} 的管道字符串同样会翻译，而且连输出别名一起翻译</b>——
 *       这一点最坑，因为改的是结果文档的键名，见 {@link com.xncoding.mongo.dto.OrderStatusStat}。</li>
 * </ol>
 * 结论有点反直觉：<b>这个框架里几乎没有"原样下发"的地方</b>，
 * 与其靠记忆猜哪条路径翻译哪条不翻译，不如写完打开
 * {@code org.mongodb.driver.protocol.command} 的 DEBUG 日志看一眼真实报文。
 * 本篇所有涉及字段名的结论都是这么测出来的。
 *
 * <p><b>取证时别用中间层日志。</b>{@code MongoTemplate} 自己的 DEBUG 日志打的是
 * <b>翻译前</b>的 {@code Query}——{@code findById} 在上面显示成
 * {@code { "id" : "..." }}，而驱动实际发出去的是
 * {@code {"find": "orders", "filter": {"_id": {"$oid": "..."}}}}。
 * 中间层那行看着最像人话，恰恰是转换之前那一步的快照；
 * 拿它当"下发了什么"的证据，会得出完全相反的结论。
 * 只有驱动层的 {@code Command: {...}} 是权威报文。
 */
@Repository
public class OrderMongoDao {

    private final MongoTemplate mongoTemplate;

    public OrderMongoDao(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 按关键字 + 状态分页，条件动态拼接。
     * <p>
     * 这是模板层最常用的形态：一个 {@link Criteria} 链，为 null 的条件不参与。
     * 上一篇（JPA）为了实现同样的效果要写 {@code Specification}——
     * 一个专门为"动态条件"设计的抽象。MongoDB 这边就是 {@link Query} 本身，
     * 不需要额外概念。
     * <p>
     * 关键字用 {@link java.util.regex.Pattern} 做不区分大小写的包含匹配。
     * <b>注意这是正则扫描，用不上索引</b>（前缀锚定的正则才能用索引，
     * 而"包含"天然不锚定）。数据量大了要么改成前缀匹配，要么上全文索引。
     *
     * @param sortField 已由 {@link com.xncoding.mongo.domain.OrderPageQuery} 白名单校验过的
     *                  <b>Java 属性名</b>。这里要用 {@code Query.with(Sort)}，
     *                  而 {@code Sort} 是 Spring Data 的类型，会做属性名翻译——
     *                  这是本类里唯一一处传属性名而不是存储名的地方。
     */
    public Page<Order> findPage(String keyword, OrderStatus status, org.springframework.data.domain.Sort sort,
                                int page, int size) {
        Query query = new Query();
        if (keyword != null && !keyword.isBlank()) {
            Criteria keywordCriteria = new Criteria().orOperator(
                    Criteria.where("order_no").regex(java.util.regex.Pattern.compile(
                            java.util.regex.Pattern.quote(keyword), java.util.regex.Pattern.CASE_INSENSITIVE)),
                    Criteria.where("customer_name").regex(java.util.regex.Pattern.compile(
                            java.util.regex.Pattern.quote(keyword), java.util.regex.Pattern.CASE_INSENSITIVE)));
            query.addCriteria(keywordCriteria);
        }
        if (status != null) {
            query.addCriteria(Criteria.where("status").is(status));
        }

        // count 要在加分页之前问，加了 skip/limit 之后 count 会被当成"本页条数"
        long total = mongoTemplate.count(query, Order.class);

        Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size, sort);
        query.with(pageable);

        List<Order> content = mongoTemplate.find(query, Order.class);
        // PageableExecutionUtils 在这条路径上省不掉 count（模板层拿不到"总数肯定等于本页大小"的信息），
        // 但用它至少能把"count 什么时候算"交给框架决定，将来换实现不用改这里。
        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }

    /**
     * 按 id 查单条。
     * <p>
     * {@code _id} 在文档里存的是 {@link org.bson.types.ObjectId}，
     * 而方法的入参是字符串。不用自己转：{@code Query} 的参数会走 Spring Data
     * 的类型转换，字符串 {@code _id} 会被转成 ObjectId 再去匹配。
     * 但<b>前提是字符串得是合法的 24 位十六进制</b>——传一个乱写的 id 进来，
     * 这里会抛 {@code ConversionFailedException}，而不是"查不到返回空"。
     * 服务层必须先挡一道（见 {@code OrderService.get}）。
     */
    public Optional<Order> findById(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, Order.class));
    }

    /**
     * 原子地把订单从一个状态改到另一个状态。
     * <p>
     * 用 {@code findAndModify} 而不是"读出来改字段再 save"，理由是并发：
     * <ul>
     *   <li>{@code save} 是整份替换，两个并发请求会互相覆盖（要 {@code @Version} 才拦得住）；</li>
     *   <li>{@code findAndModify} 把"条件 + 更新"合成一条命令发给 MongoDB，
     *       服务端保证原子性。<b>条件里带上当前状态</b>，
     *       就等于把"状态机校验"也做进了原子操作里——
     *       不满足条件时 {@code matchedCount} 是 0，返回 null。
     *       这是关系型那边要靠 {@code select ... for update} 或者乐观锁才能达到的效果。</li>
     * </ul>
     * {@code inc("version", 1)} 是顺手把版本号推一格：虽然这个更新不受
     * {@code @Version} 保护，但版本号保持一致，客户端下次 {@code PUT} 才不会莫名撞版本。
     * <p>
     * {@code FindAndModifyOptions.options().returnNew(true)} 必须加，
     * 否则默认返回的是<b>更新前</b>的文档——这是个经典陷阱，
     * 不写的话接口返回的状态永远是旧的那个。
     *
     * @return 更新后的订单；条件不满足（状态已被别人改过、或文档不存在）时返回空
     */
    public Optional<Order> transferStatus(String id, OrderStatus expectedStatus, OrderStatus targetStatus) {
        Query query = Query.query(Criteria.where("_id").is(id).and("status").is(expectedStatus));
        Update update = new Update().set("status", targetStatus).inc("version", 1);
        Order updated = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Order.class);
        return Optional.ofNullable(updated);
    }

    /**
     * 往明细数组里追加一条（{@code $push}），服务端原子操作。
     * <p>
     * 不必先读出来、改数组、再整份写回去。并发追加两条明细也不会丢其中一条——
     * 而"读-改-写"一定会丢（后写的覆盖先写的）。
     *
     * @return 更新后的订单；订单不存在时返回空
     */
    public Optional<Order> pushItem(String id, OrderItem item) {
        Query query = Query.query(Criteria.where("_id").is(id));
        Update update = new Update().push("items", item).inc("version", 1);
        Order updated = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Order.class);
        return Optional.ofNullable(updated);
    }

    /**
     * 从明细数组里移除指定商品（{@code $pull}）。
     * <p>
     * {@code $pull} 的行为值得记住：当它收到的值是一个文档时，
     * MongoDB 会把它当成<b>条件</b>而不是"整份相等"，
     * 也就是"移除所有 {@code product_name} 等于这个值的元素"，
     * 而不是"移除某个字段完全一致的完整元素"。多条同名商品会一起被移除。
     *
     * @return 实际被改动的文档数（0 表示订单不存在或没有这个商品）
     */
    public long pullItem(String id, String productName) {
        Query query = Query.query(Criteria.where("_id").is(id));
        // 条件型 $pull：传一个 Document，MongoDB 按字段条件匹配数组元素
        Update update = new Update().pull("items", new Document("product_name", productName));
        return mongoTemplate.updateFirst(query, update, Order.class).getModifiedCount();
    }

    /**
     * 用模板层搭聚合管道做状态统计。
     * <p>
     * 效果和 {@code OrderRepository.statByStatus()} 完全一样，两种写法都留着，
     * 是为了看清楚代价：注解版管道写死在接口上，条件一变就得改方法签名；
     * 代码版可以在 Java 里按参数决定要不要加阶段。管道复杂起来只能用这一种。
     * <p>
     * <b>注意这里 {@code .as("total_amount")} 用的是存储名，看起来别扭，是有原因的。</b>
     * 注解版那条管道里我写的是 {@code totalAmount}，但 {@code @Aggregation} 会把它
     * 按实体属性名改写成 {@code total_amount}（见 {@code OrderStatusStat} 的类注释，
     * 打开命令日志能看到改写前后的差别）。而 fluent API <b>不会</b>改写别名——
     * 写什么就是什么。为了让两条路径最终落在同一个键名上、也为了让返回类型
     * 用的是同一个映射，这里主动跟着写成存储名。
     * <p>
     * 换个说法：<b>聚合输出的键名不要凭感觉起，两条实现路径的规则不一样，
     * 写完打开 {@code org.mongodb.driver.protocol.command} 的 DEBUG 日志看一眼报文最省事。</b>
     */
    public List<OrderStatusStat> statByStatus() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group("status")
                        .count().as("count")
                        .sum("total_amount").as("total_amount"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "_id"));
        AggregationResults<OrderStatusStat> results =
                mongoTemplate.aggregate(aggregation, Order.class, OrderStatusStat.class);
        return results.getMappedResults();
    }

    /**
     * <b>故意的反例：把聚合输出别名按"最自然的写法"起，然后就丢了数据。</b>
     * <p>
     * 和 {@link #statByStatus()} 唯一的区别就是 {@code .as()} 里写的是
     * {@code totalAmount} 而不是 {@code total_amount}。看上去这才是对的
     * ——毕竟返回类型 {@link OrderStatusStat} 上的属性也叫 {@code totalAmount}。
     * <p>
     * 但结果是：{@code totalAmount} 这一项在响应里<b>整个消失</b>。
     * 原因是 {@code OrderStatusStat.totalAmount} 标了
     * {@code @Field("total_amount")}，映射层只认 {@code total_amount} 这个键；
     * 而 fluent API 又不会像 {@code @Aggregation} 那样把别名翻译一遍，
     * 于是 {@code totalAmount} 这个键就这么原样躺在结果文档里，没人来接。
     * <p>
     * 这个接口留着只有一个用途：让人亲眼看见"聚合输出的键名"和"实体属性名"
     * 是两套东西。真实项目里别这么写。
     */
    public List<OrderStatusStat> statByStatusNaive() {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.group("status")
                        .count().as("count")
                        // 这里就是那个"看起来更自然"的写法
                        .sum("total_amount").as("totalAmount"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "_id"));
        AggregationResults<OrderStatusStat> results =
                mongoTemplate.aggregate(aggregation, Order.class, OrderStatusStat.class);
        return results.getMappedResults();
    }

    /**
     * 商品维度的销量排行，用 {@code $unwind} 把内嵌数组摊平。
     * <p>
     * 这是文档型存储最不直观的一步。订单里明细是一个数组字段，
     * 想按"商品"这个粒度统计，就得先把数组拆开——{@code $unwind: '$items'}
     * 会让一条含 2 个商品的订单变成 2 条记录，每条带一个商品。
     * 这等价于关系型的 {@code join t_order_item}，但不需要提前把明细建模成独立实体。
     * <p>
     * 销售额要现算：{@code $multiply: ['$items.price', '$items.quantity']}。
     * 因为 {@code amount} 在实体上标了 {@code @Transient}，文档里根本没有这个字段。
     * <p>
     * 返回类型是 record，而且结果是<b>手工映射</b>的——{@code $unwind} 之后
     * group key 还是叫 {@code _id}，但这次它装的是商品名字符串，
     * 直接交给转换器映射到 {@code ProductSales.productName} 上会失败。
     * 与其加一堆 {@code @Field("_id")} 到处救火，不如在数据访问层就把 BSON 文档
     * 转成领域对象，让上层拿到的东西是类型安全的。这也是模板层该承担的职责。
     *
     * @param limit 取前几名
     */
    public List<ProductSales> topProducts(int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.unwind("items"),
                Aggregation.group("items.product_name")
                        .sum("items.quantity").as("totalQuantity")
                        .sum(org.springframework.data.mongodb.core.aggregation.ArithmeticOperators
                                .Multiply.valueOf("items.price").multiplyBy("items.quantity")).as("salesAmount"),
                Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "totalQuantity"),
                Aggregation.limit(limit));
        List<Document> rows = mongoTemplate
                .aggregate(aggregation, mongoTemplate.getCollectionName(Order.class), Document.class)
                .getMappedResults();
        return rows.stream()
                .map(row -> new ProductSales(
                        row.getString("_id"),
                        ((Number) row.get("totalQuantity")).longValue(),
                        toBigDecimal(row.get("salesAmount"))))
                .toList();
    }

    /**
     * 某个状态下的订单数。
     * <p>
     * {@code count} 在 MongoDB 里是一条单独的 count 命令，不走文档传输，
     * 比"查出来再 size()"省得多。仓储里也有 {@code countByStatus}，
     * 这里留着是为了演示带 {@link Criteria} 的条件计数。
     */
    public long countByStatus(OrderStatus status) {
        Query query = Query.query(Criteria.where("status").is(status));
        return mongoTemplate.count(query, Order.class);
    }

    /**
     * 取当前出现过的所有状态（去重）。
     * <p>
     * {@code distinct} 也是服务端做去重，只把结果集传回来。
     * 在关系型里这对应 {@code select distinct status from ...}。
     * MongoDB 的 distinct 有个额外限制：结果集超过 16MB 会报错，
     * 因为它是作为单条命令的返回值传回来的。状态这种低基数字段当然没问题，
     * 换成"去重所有订单号"就危险了——那种场景要用聚合的 {@code $group}。
     */
    public List<OrderStatus> distinctStatuses() {
        return mongoTemplate.findDistinct(new Query(), "status", Order.class, OrderStatus.class);
    }

    /** {@code $sum} 出来的可能是 Decimal128、Integer 或 Double，统一收敛成 BigDecimal */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof org.bson.types.Decimal128 decimal128) {
            return decimal128.bigDecimalValue();
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return new BigDecimal(value.toString());
    }
}
