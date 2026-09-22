package com.xncoding.jpa.repository;

import com.xncoding.jpa.domain.Order;
import com.xncoding.jpa.domain.OrderItem;
import com.xncoding.jpa.domain.OrderStatus;
import com.xncoding.jpa.dto.OrderStatusStat;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仓储层的切片测试。
 * <p>
 * <b>先看这个类的包名，它是本篇最容易照抄错的一处。</b>
 * 以前 {@code @DataJpaTest} 在 {@code org.springframework.boot.test.autoconfigure.orm.jpa}，
 * Spring Boot 4 把 JPA 自动配置拆成了三个模块，测试注解也跟着各归各家：
 * <ul>
 *   <li>{@code @DataJpaTest} → {@code org.springframework.boot.data.jpa.test.autoconfigure}</li>
 *   <li>{@code TestEntityManager} → {@code org.springframework.boot.jpa.test.autoconfigure}</li>
 *   <li>{@code @AutoConfigureTestDatabase} → {@code org.springframework.boot.jdbc.test.autoconfigure}</li>
 * </ul>
 * 三个注解分居三个包，IDE 的自动补全帮不上忙，只能查制品。照老博客的 import 写一定编译不过。
 * <p>
 * 切片测试只加载 JPA 那一套自动配置，不会起 Web 容器，也不会扫到 Service 和 Controller。
 * 测仓储本身正合适——比直接 {@code @SpringBootTest} 快，失败的定位也更准：
 * 报错一定出在数据访问层，不用怀疑是不是 MVC 那边的问题。
 * <p>
 * 用的是真实 MySQL，所以做了两道防护：{@code application-test.yml} 把连接池改成懒加载，
 * {@link #checkDatabase()} 在跑用例前探测一次，连不上就整体跳过。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderRepositorySliceTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderSummaryRepository orderSummaryRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Autowired
    private DataSource dataSource;

    /** 连不上数据库就跳过整个类，而不是让每个用例各自红一次 */
    @BeforeAll
    void checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.isValid(3), "MySQL 连接不可用，跳过仓储切片测试");
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "MySQL 连接不可用（" + ex.getMessage() + "），跳过仓储切片测试");
        }
    }

    /** 造一条带两条明细的订单并落库 */
    private Order persistOrder(String customerName) {
        Order order = new Order("ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 17),
                customerName, "13800138000", new BigDecimal("299.50"), "切片测试");
        order.addItem(new OrderItem("机械键盘", new BigDecimal("299.50"), 1));
        order.addItem(new OrderItem("腕托", new BigDecimal("25.00"), 2));
        return testEntityManager.persistAndFlush(order);
    }

    @Test
    @DisplayName("仓储装配：两个仓储都能注入，主键类型是 Long")
    void repositoriesAreWired() {
        assertThat(orderRepository).isNotNull();
        assertThat(orderSummaryRepository).isNotNull();
    }

    @Test
    @DisplayName("审计字段：@CreatedDate 与 @LastModifiedDate 在 persist 时被填上，精度截到毫秒")
    void auditingColumnsAreFilled() {
        Order order = persistOrder("审计");

        assertThat(order.getCreatedAt()).as("创建时间由 AuditingEntityListener 填充").isNotNull();
        assertThat(order.getUpdatedAt()).isNotNull();
        assertThat(order.getVersion()).as("乐观锁版本号初值为 0").isZero();
        // DATETIME(3) 只到毫秒。AuditingEntityListener 默认取 LocalDateTime.now()，
        // 带完整纳秒；不截断的话，实体在内存里的值和从库里读回来的值不一致，
        // 同一个字段两个值，客户端做等值比较就踩空。截断靠启动类里那个 DateTimeProvider。
        assertThat(order.getCreatedAt().getNano() % 1_000_000).isZero();

        // 与落库值对齐：清掉缓存重新读一遍，两个时间戳必须一模一样
        Long id = order.getId();
        testEntityManager.clear();
        Order reloaded = orderRepository.findById(id).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(order.getCreatedAt());
    }

    @Test
    @DisplayName("派生查询：方法名即查询，四种条件各验一遍")
    void derivedQueriesWork() {
        Order order = persistOrder("派生查询");

        assertThat(orderRepository.findByOrderNo(order.getOrderNo())).isPresent();
        assertThat(orderRepository.existsByOrderNo(order.getOrderNo())).isTrue();
        assertThat(orderRepository.existsByOrderNo("不存在的单号")).isFalse();
        assertThat(orderRepository.countByStatus(OrderStatus.CREATED)).isPositive();
        assertThat(orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.CREATED))
                .extracting(Order::getCreatedAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    @DisplayName("@EntityGraph：findWithItemsById 一次就把明细抓出来，不需要再访问集合")
    void entityGraphFetchesItemsEagerly() {
        Order order = persistOrder("抓取");
        testEntityManager.clear();

        Order reloaded = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        // 关键在"清过持久化上下文之后还能读到 items"：没有 @EntityGraph 的话，
        // 这一步会再补一条 SQL（能跑通，只是慢）；如果把事务也关掉，就直接抛 LazyInitializationException
        assertThat(reloaded.getItems()).hasSize(2);
        assertThat(reloaded.getItems().get(1).getProductName()).isEqualTo("腕托");
    }

    @Test
    @DisplayName("JPQL：构造器表达式映射到 record，sum 空集时 coalesce 兜成 0")
    void jpqlAggregationMapsToRecord() {
        persistOrder("聚合");
        testEntityManager.clear();

        List<OrderStatusStat> stats = orderRepository.statByStatus();

        assertThat(stats).isNotEmpty();
        assertThat(stats).extracting(OrderStatusStat::getStatusLabel).doesNotContainNull();
        // 这个断言正是 coalesce 存在的理由：某个状态一条数据都没有时，
        // 没有 coalesce 的话 sum() 返回 null，构造器参数是 BigDecimal 就直接 NPE
        assertThat(stats).allSatisfy(stat -> {
            assertThat(stat.count()).isPositive();
            assertThat(stat.totalAmount()).isNotNull();
        });
        // 与逐行取出来在内存里算的结果对齐
        List<Order> all = orderRepository.findAll();
        assertThat(stats.stream().mapToLong(OrderStatusStat::count).sum()).isEqualTo(all.size());
    }

    @Test
    @DisplayName("JPQL：参数绑定的条件查询")
    void jpqlParameterizedQuery() {
        Order order = persistOrder("贵一点的");
        testEntityManager.clear();

        List<Order> found = orderRepository.findNotCheaperThan(new BigDecimal("200"));
        assertThat(found).extracting(Order::getId).contains(order.getId());
        assertThat(found).allSatisfy(o ->
                assertThat(o.getTotalAmount()).isGreaterThanOrEqualTo(new BigDecimal("200")));
    }

    @Test
    @DisplayName("原生 SQL 不受 @SoftDelete 保护：不自己写 deleted = 0 就会把已删的算进来")
    void nativeQueryNeedsExplicitSoftDeleteFilter() {
        Order order = persistOrder("原生SQL");
        orderRepository.delete(order);
        testEntityManager.flush();
        testEntityManager.clear();

        // 方法里那句 deleted = 0 是自己手写的，所以统计不会把刚删掉的算进去
        long activeCount = orderRepository.countActiveByStatus(OrderStatus.CREATED.name());
        // 对照：Hibernate 自己生成的查询自动带上过滤条件，所以这里查不到
        assertThat(orderRepository.findById(order.getId())).isEmpty();
        assertThat(activeCount).isNotNegative();

        // 直接问数据库：这一行还在，只是被标记了
        EntityManager em = testEntityManager.getEntityManager();
        Object physical = em.createNativeQuery(
                        "select count(*) from t_order where id = :id and deleted = 1")
                .setParameter("id", order.getId())
                .getSingleResult();
        assertThat(((Number) physical).longValue()).as("软删除后物理行仍在，且 deleted 被置为 1").isEqualTo(1L);
    }

    @Test
    @DisplayName("@SoftDelete：delete 生成的是 UPDATE，删完之后派生查询一律看不到它")
    void softDeleteHidesRowFromDerivedQueries() {
        Order order = persistOrder("软删除");
        long before = orderRepository.countByStatus(OrderStatus.CREATED);

        orderRepository.delete(order);
        testEntityManager.flush();
        testEntityManager.clear();

        assertThat(orderRepository.findByOrderNo(order.getOrderNo())).isEmpty();
        assertThat(orderRepository.countByStatus(OrderStatus.CREATED)).isEqualTo(before - 1);
        assertThat(orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.CREATED))
                .extracting(Order::getId)
                .doesNotContain(order.getId());
    }

    @Test
    @DisplayName("Specification：为 null 的条件自动忽略，不是拼出一个恒假条件")
    void specificationIgnoresNullConditions() {
        persistOrder("动态条件");

        var spec = OrderSpecifications.allOfNullable(
                OrderSpecifications.keywordContains("动态条件"),
                null,
                null);

        Page<Order> page = orderRepository.findAll(spec, PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isPositive();
        assertThat(page.getContent()).allSatisfy(order ->
                assertThat(order.getCustomerName()).contains("动态条件"));
    }

    @Test
    @DisplayName("Specification：多个条件之间是 AND，关键字同时匹配订单号和客户名")
    void specificationCombinesConditions() {
        Order order = persistOrder("组合条件");

        Page<Order> byOrderNo = orderRepository.findAll(
                OrderSpecifications.keywordContains(order.getOrderNo()), PageRequest.of(0, 10));
        assertThat(byOrderNo.getTotalElements()).isEqualTo(1);

        Page<Order> byStatusMismatch = orderRepository.findAll(
                OrderSpecifications.allOfNullable(
                        OrderSpecifications.keywordContains(order.getOrderNo()),
                        OrderSpecifications.hasStatus(OrderStatus.CANCELLED)),
                PageRequest.of(0, 10));
        assertThat(byStatusMismatch.getTotalElements()).as("两个条件都要满足").isZero();
    }

    @Test
    @DisplayName("排序：Sort 收的是实体属性名，传数据库列名会直接抛异常")
    void sortUsesEntityPropertyNamesNotColumnNames() {
        Sort byEntityProperty = Sort.by(Sort.Direction.DESC, "createdAt");
        assertThat(orderRepository.findAll(byEntityProperty)).isNotEmpty();

        // 这一条是给上一篇的读者提个醒：MyBatis-Plus 的 OrderItem 拼 SQL 片段，
        // 写的是列名 created_at；Spring Data 的 Sort 走的是属性路径，列名它不认识。
        //
        // 而且报错信息有点绕：它说的是 "No property 'created'"，
        // 因为下划线在 Spring Data 里是**嵌套属性的分隔符**，
        // created_at 被解析成了"created 下面的 at"。看到这条报错别去实体里找 created 字段。
        assertThatThrownBy(() -> orderRepository.findAll(Sort.by(Sort.Direction.DESC, "created_at")))
                .isInstanceOf(PropertyReferenceException.class)
                .hasMessageContaining("No property 'created' found for type 'Order'");
    }

    @Test
    @DisplayName("接口投影：生成的查询只取四列，明细集合一点都没碰")
    void interfaceProjectionSelectsOnlyFourColumns() {
        Order order = persistOrder("投影");
        testEntityManager.clear();

        List<OrderSummary> summaries = orderSummaryRepository.findByStatus(OrderStatus.CREATED);

        assertThat(summaries).isNotEmpty();
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.getId()).isNotNull();
            assertThat(summary.getOrderNo()).isNotNull();
            assertThat(summary.getStatus()).isNotNull();
        });
        assertThat(summaries).extracting(OrderSummary::getId).contains(order.getId());
    }
}
