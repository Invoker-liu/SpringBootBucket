package com.xncoding.mongo.repository;

import com.mongodb.MongoCommandException;
import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderItem;
import com.xncoding.mongo.domain.OrderStatus;
import com.xncoding.mongo.dto.OrderStatusStat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据访问层的切片测试。
 * <p>
 * <b>先看这个类的包名，它是本篇最容易照抄错的一处。</b>
 * 以前 {@code @DataMongoTest} 在
 * {@code org.springframework.boot.test.autoconfigure.data.mongo}，
 * Boot 4 把 MongoDB 自动配置拆成两个模块，测试注解也跟着各归各家：
 * <ul>
 *   <li>{@code @DataMongoTest} → {@code org.springframework.boot.data.mongodb.test.autoconfigure}（缓存模块）</li>
 * </ul>
 * 同一个包里还有 {@code @AutoConfigureDataMongo}、{@code DataMongoTestContextBootstrapper}、
 * {@code DataMongoTypeExcludeFilter}。和 01 篇的 {@code webmvc.test.autoconfigure}、
 * 03 篇的 {@code data.jpa.test.autoconfigure} 是同一套规律：
 * <b>测试自动配置按技术模块分包，包名 = 模块路径 + {@code .test.autoconfigure}</b>。
 * <p>
 * 切片测试只加载 MongoDB 那一套自动配置，不起 Web 容器，也不会扫到 Service 和 Controller。
 * 测数据访问本身正合适——报错一定出在数据层，不用怀疑是不是 MVC 那边的问题。
 * <p>
 * 用的是真实 MongoDB，连不上就整体 skip，不做"一片红"。
 */
@DataMongoTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// 这里必须显式 @Import。@DataMongoTest 收的是"Spring Data 仓储"，
// 也就是 OrderRepository / OrderSummaryRepository 这种由框架生成代理的接口；
// 自己写的 @Repository 类（OrderMongoDao）它一个都不扫。
// 不写这一行，注入 orderMongoDao 会直接报 NoSuchBeanDefinitionException。
@Import(OrderMongoDao.class)
class OrderRepositorySliceTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSummaryRepository orderSummaryRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    /** 模板层那个自定义 DAO，@DataMongoTest 会把它当 @Repository 一起收进来 */
    @Autowired
    private OrderMongoDao orderMongoDao;

    /** 连不上数据库就跳过整个类，而不是让每个用例各自红一次 */
    @BeforeAll
    void checkDatabase() {
        try {
            mongoTemplate.executeCommand("{ ping: 1 }");
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "MongoDB 不可用（" + ex.getMessage() + "），跳过数据访问层切片测试");
        }
    }

    /**
     * 每个用例前清空集合。<b>这里没有事务可用</b>——单机 MongoDB 不支持多文档事务，
     * 所以切片测试也不像 JPA 那篇能靠 {@code @Transactional} 自动回滚，
     * 只能老老实实手动清。好在测试跑在独立的 {@code springboot4_mongo_test} 库上，
     * 清空不会有任何副作用。
     * <p>
     * 注意 {@code deleteAll()} 只删文档、<b>不删索引</b>，所以唯一索引在整个类里一直有效。
     */
    @BeforeEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    private Order persistOrder(String customerName) {
        Order order = new Order("ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 17),
                customerName, "13800138000", new BigDecimal("299.50"), "切片测试");
        order.addItem(new OrderItem("机械键盘", new BigDecimal("299.50"), 1));
        order.addItem(new OrderItem("腕托", new BigDecimal("25.00"), 2));
        return orderRepository.save(order);
    }

    // ------------------------------------------------------------------
    // 装配与主键
    // ------------------------------------------------------------------

    @Test
    @DisplayName("仓储装配：两个仓储都能注入，主键是 ObjectId 字符串")
    void repositoriesAreWired() {
        assertThat(orderRepository).isNotNull();
        assertThat(orderSummaryRepository).isNotNull();

        Order order = persistOrder("装配");
        // 24 位十六进制，这是 ObjectId 的标准字符串形态
        assertThat(order.getId()).isNotNull().hasSize(24).matches("[0-9a-f]{24}");
    }

    @Test
    @DisplayName("审计字段：@CreatedDate 与 @LastModifiedDate 被填上，且精度天然只到毫秒")
    void auditingColumnsAreFilled() {
        Order order = persistOrder("审计");

        assertThat(order.getCreatedAt()).as("创建时间由审计回调填充").isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
        assertThat(order.getVersion()).as("乐观锁版本号初值为 0").isZero();

        // BSON 日期就是毫秒整数，所以这里一定是 0 —— 和 MySQL 那篇不同，
        // 那边是 DATETIME(3) 的列精度限制，这边是存储格式本身没有纳秒。
        // 两边的结论一样、原因不一样，但都要求我们在源头截断成毫秒：
        // 否则内存里那份 Java 对象还带着纳秒，写下去的被丢了，读回来的自然对不上。
        assertThat(order.getCreatedAt().getNano() % 1_000_000).isZero();

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(order.getCreatedAt());
        assertThat(reloaded.getUpdatedAt()).isEqualTo(order.getUpdatedAt());
    }

    // ------------------------------------------------------------------
    // 索引：这一节是本篇的核心证据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("索引：auto-index-creation 打开时，@Indexed 与 @CompoundIndex 真的建出来了")
    void indexesAreCreatedWhenAutoIndexCreationEnabled() {
        // 先写一条，触发集合创建与索引扫描
        persistOrder("索引");

        List<String> names = mongoTemplate.indexOps(Order.class).getIndexInfo().stream()
                .map(IndexInfo::getName)
                .toList();

        assertThat(names).as("唯一索引来自 @Indexed(unique = true)").contains("uk_order_no");
        assertThat(names).as("普通索引来自 @Indexed").contains("idx_status");
        assertThat(names).as("复合索引来自 @CompoundIndex").contains("idx_status_created_at");

        // 复合索引的字段顺序必须是 status 在前、created_at 在后，
        // 反了就覆盖不了"按状态筛、按时间倒排"这个查询
        IndexInfo compound = mongoTemplate.indexOps(Order.class).getIndexInfo().stream()
                .filter(info -> "idx_status_created_at".equals(info.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(compound.getIndexFields()).extracting(IndexField::getKey)
                .containsExactly("status", "created_at");
        assertThat(compound.getIndexFields()).extracting(IndexField::getDirection)
                .as("status 升序、created_at 降序，正好配合'按状态筛 + 按时间倒排'")
                .containsExactly(Sort.Direction.ASC, Sort.Direction.DESC);

        // 唯一性是最容易漏掉的一项：名字建出来了、字段也对，但忘了 unique，
        // 索引就是一个普通的索引，唯一约束形同虚设
        IndexInfo uniqueIndex = mongoTemplate.indexOps(Order.class).getIndexInfo().stream()
                .filter(info -> "uk_order_no".equals(info.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(uniqueIndex.isUnique()).as("uk_order_no 必须是唯一索引").isTrue();
    }

    @Test
    @DisplayName("唯一索引生效：同一个订单号插第二次抛 DuplicateKeyException")
    void uniqueIndexBlocksDuplicateOrderNo() {
        Order first = persistOrder("唯一索引");

        Order duplicate = new Order(first.getOrderNo(), "另一个人", "13900139000",
                new BigDecimal("10.00"), "撞单号");
        assertThatThrownBy(() -> orderRepository.save(duplicate))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(orderRepository.count()).as("第二条没写进去").isEqualTo(1);
    }

    @Test
    @DisplayName("集合名：@Document(collection = \"orders\") 生效，不是默认的 order")
    void collectionNameComesFromAnnotation() {
        persistOrder("集合名");

        assertThat(mongoTemplate.getCollectionName(Order.class)).isEqualTo("orders");
        assertThat(mongoTemplate.getCollection("orders").countDocuments()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 字段名映射：属性名 vs 存储名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("字段名映射：@Field 改过的字段按存储名落库，派生查询仍用属性名")
    void fieldNamesAreMappedToStoredNames() {
        Order order = persistOrder("字段名");

        // 直接看文档：createdAt 存成了 created_at，orderNo 存成了 order_no，
        // productName 存成了 product_name，而 status、items、version 保持原名
        var raw = mongoTemplate.getCollection("orders")
                .find(new org.bson.Document("_id", new org.bson.types.ObjectId(order.getId())))
                .first();
        assertThat(raw).isNotNull();
        assertThat(raw.keySet()).contains("order_no", "customer_name", "customer_phone",
                "total_amount", "created_at", "updated_at", "items", "status", "version");
        assertThat(raw.keySet()).as("不该出现 Java 属性名").doesNotContain("orderNo", "createdAt", "totalAmount");

        // 派生查询的方法名用的是 Java 属性名，框架负责翻译
        assertThat(orderRepository.findByOrderNo(order.getOrderNo())).isPresent();
        // 排序也一样
        assertThat(orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.CREATED)).isNotEmpty();
    }

    @Test
    @DisplayName("手写 @Query 也会翻译字段名：存储名和属性名两种写法结果完全一致")
    void queryAnnotationDoesTranslateFieldNames() {
        persistOrder("手写查询");

        List<Order> byStoredName = orderRepository.findByItemProductName("机械键盘");
        List<Order> byPropertyName = orderRepository.findByItemProductNameWithPropertyName("机械键盘");

        // 这一条是本次实测最反直觉的结论之一：我原本以为 @Query 里的 JSON 是
        // "原样下发"，写 items.productName 会一条都查不到。实测是两种写法都能查到，
        // Spring Data 在下发前把 items.productName 换成了 items.product_name。
        assertThat(byStoredName).hasSize(1);
        assertThat(byPropertyName).as("手写 JSON 同样会被属性名映射").hasSize(1);
        assertThat(byStoredName.get(0).getId()).isEqualTo(byPropertyName.get(0).getId());

        // 商品名本身不存在时当然还是空——这是真的"查不到"，
        // 和"字段名写错"在结果上分辨不出来，所以才要专门做上面那个对照
        assertThat(orderRepository.findByItemProductName("不存在的商品")).isEmpty();
    }

    @Test
    @DisplayName("Update 的字段名同样会被翻译：set 属性名落到存储名上，不会多出同义字段")
    void updateAlsoTranslatesFieldNames() {
        Order order = persistOrder("更新映射");

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(order.getId())),
                new Update().set("customerName", "改过名"),
                Order.class);

        var raw = mongoTemplate.getCollection("orders")
                .find(new org.bson.Document("_id", new org.bson.types.ObjectId(order.getId())))
                .first();
        assertThat(raw).isNotNull();
        assertThat(raw.get("customer_name")).isEqualTo("改过名");
        // 如果 Update 不做映射，这里会多出一个叫 customerName 的字段，
        // 于是同一条文档里两个字段各存一份"客户名"，读的时候只认一个，
        // 另一个永远不被读也不被清，是最难查的一类脏数据
        assertThat(raw.keySet()).doesNotContain("customerName");
    }

    // ------------------------------------------------------------------
    // 内嵌数组与聚合
    // ------------------------------------------------------------------

    @Test
    @DisplayName("内嵌数组：明细跟着订单一起写下去，读回来是完整数组，没有懒加载")
    void itemsAreEmbeddedAndComeBackWhole() {
        Order order = persistOrder("内嵌数组");

        Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getItems()).hasSize(2);
        assertThat(reloaded.getItems()).extracting(OrderItem::getProductName)
                .containsExactly("机械键盘", "腕托");
        // amount 标了 @Transient，不该落库；读回来时由 getter 现算
        assertThat(reloaded.getItems().get(0).getAmount()).isEqualByComparingTo("299.50");
    }

    @Test
    @DisplayName("$push / $pull：数组元素的服务端原子增删，不用整份替换文档")
    void arrayElementsAreUpdatedAtomically() {
        Order order = persistOrder("原子增删");

        // $push
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(order.getId())),
                new Update().push("items", new OrderItem("鼠标垫", new BigDecimal("88.00"), 1)),
                Order.class);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getItems()).hasSize(3);

        // $pull：注意 $pull 收到文档时按"条件"匹配数组元素，同名商品会被一起移除
        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(order.getId())),
                new Update().pull("items", new org.bson.Document("product_name", "腕托")),
                Order.class);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getItems())
                .extracting(OrderItem::getProductName)
                .containsExactly("机械键盘", "鼠标垫");
    }

    @Test
    @DisplayName("聚合：@Aggregation 声明的管道能跑通，group key 由 @Field(\"_id\") 接住")
    void aggregationPipelineMapsGroupKey() {
        persistOrder("聚合一");
        persistOrder("聚合二");

        List<OrderStatusStat> stats = orderRepository.statByStatus();

        assertThat(stats).isNotEmpty();
        assertThat(stats).extracting(OrderStatusStat::getStatus).contains(OrderStatus.CREATED);
        assertThat(stats).allSatisfy(stat -> {
            assertThat(stat.getCount()).isPositive();
            assertThat(stat.getStatusLabel()).isNotBlank();
            assertThat(stat.getTotalAmount()).as("$sum 对空集返回 0，不会是 null").isNotNull();
        });
        assertThat(stats.stream().mapToLong(OrderStatusStat::getCount).sum())
                .isEqualTo(orderRepository.count());
    }

    @Test
    @DisplayName("聚合输出别名：fluent API 写 totalAmount，结果里就没有 total_amount，映射不到、静默丢列")
    void fluentAggregationDoesNotRewriteOutputAlias() {
        persistOrder("别名反例一");
        persistOrder("别名反例二");

        // 正例：.as("total_amount") 跟 @Field 对上，金额拿得到
        List<OrderStatusStat> aligned = orderMongoDao.statByStatus();
        assertThat(aligned).isNotEmpty();
        assertThat(aligned).allSatisfy(stat ->
                assertThat(stat.getTotalAmount()).as("对齐后金额是有的").isNotNull());

        // 反例：.as("totalAmount") —— 写的是实体属性名，看着更自然，但没人来接这个键
        List<OrderStatusStat> naive = orderMongoDao.statByStatusNaive();

        assertThat(naive).as("行数照样对，count 也照样对").isNotEmpty();
        assertThat(naive.stream().mapToLong(OrderStatusStat::getCount).sum())
                .isEqualTo(aligned.stream().mapToLong(OrderStatusStat::getCount).sum());
        assertThat(naive).allSatisfy(stat -> {
            assertThat(stat.getStatus()).isNotNull();
            assertThat(stat.getTotalAmount())
                    .as("写 totalAmount 就真的只剩 totalAmount 这个键，@Field(\"total_amount\") 接不到")
                    .isNull();
        });

        // 顺手确认一件事：@Aggregation 那侧注解里写的也是 totalAmount，
        // 但它下发到服务端之前会被翻译成 total_amount，所以那边不用手写存储名。
        // 两条路径规则不同，这才是最容易记反的地方。
        assertThat(orderRepository.statByStatus()).allSatisfy(stat ->
                assertThat(stat.getTotalAmount()).isNotNull());
    }

    // ------------------------------------------------------------------
    // 分页与投影
    // ------------------------------------------------------------------

    @Test
    @DisplayName("分页：Page 会额外发一条 count，total 是集合总数而不是本页条数")
    void pagingReturnsTotalFromSeparateCount() {
        persistOrder("分页一");
        persistOrder("分页二");
        persistOrder("分页三");

        Page<Order> page = orderRepository.findByStatus(OrderStatus.CREATED, PageRequest.of(0, 2,
                Sort.by(Sort.Direction.DESC, "createdAt")));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("接口投影：只查了四个字段，其余字段没有被拉回来")
    void interfaceProjectionSelectsOnlyDeclaredFields() {
        persistOrder("投影");

        List<OrderSummary> summaries = orderSummaryRepository.findByStatus(OrderStatus.CREATED);

        assertThat(summaries).isNotEmpty();
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.getId()).isNotNull();
            assertThat(summary.getOrderNo()).isNotNull();
            assertThat(summary.getCustomerName()).isNotNull();
            assertThat(summary.getStatus()).isNotNull();
        });
    }

    @Test
    @DisplayName("手写 fields 投影：返回的 Order 里没查的字段全是 null")
    void fieldsProjectionLeavesOtherFieldsNull() {
        persistOrder("fields投影");

        Order partial = orderRepository.findByStatusWithFields(OrderStatus.CREATED).get(0);

        assertThat(partial.getOrderNo()).isNotNull();
        assertThat(partial.getCustomerName()).isNotNull();
        // 这几个字段没有被投影进查询，所以是 null —— 这不是 bug，是投影的定义。
        // 也正因如此，投影结果不该直接当响应体，更不该拿去做 save()：
        // 那些 null 会把库里真实的值整片覆盖掉。
        assertThat(partial.getRemark()).isNull();
        assertThat(partial.getItems()).isEmpty();
        assertThat(partial.getVersion()).isNull();
        assertThat(partial.getCreatedAt()).isNull();
    }

    @Test
    @DisplayName("distinct：状态去重由服务端完成")
    void distinctIsEvaluatedOnServer() {
        persistOrder("去重");

        List<OrderStatus> statuses = mongoTemplate.findDistinct(new Query(), "status", Order.class, OrderStatus.class);

        assertThat(statuses).contains(OrderStatus.CREATED);
    }
}
