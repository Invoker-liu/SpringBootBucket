package com.xncoding.mybatis;

import com.xncoding.mybatis.domain.Order;
import com.xncoding.mybatis.dto.OrderResponse;
import com.xncoding.mybatis.dto.OrderStatusStat;
import com.xncoding.mybatis.mapper.OrderMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单接口的集成测试，直接跑在真实的 MySQL 上。
 * <p>
 * 为什么不换成内存库：本篇要验证的分页插件、{@code @TableLogic}、乐观锁自增，
 * 都是要靠真实数据库才说得清的行为，换成 H2 就成了"测了个仿制品"。
 * 代价是测试依赖外部数据库，所以做了两件事让它在没库的机器上不至于报错：
 * <ol>
 *   <li>{@code application-test.yml} 把 Hikari 改成懒加载，上下文创建阶段不连库；</li>
 *   <li>{@link #checkDatabase()} 在跑用例前探测一次，连不上就整体跳过。</li>
 * </ol>
 * 类上加 {@code @Transactional}，每个用例结束后事务回滚——测试可以反复跑，
 * 不会往演示数据里堆积垃圾，也不需要每次先重置数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderApiIntegrationTest {

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    /** 注入 Mapper 是为了在少数用例里绕过 HTTP 层，直接核对数据库里的值 */
    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private DataSource dataSource;

    /**
     * 探测数据库可用性。连不上就跳过整个测试类，而不是让每个用例各自红一次。
     */
    @BeforeAll
    void checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.isValid(3),
                    "MySQL 连接不可用，跳过需要数据库的集成测试");
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "MySQL 连接不可用（" + ex.getMessage() + "），跳过需要数据库的集成测试");
        }
    }

    /** 用随机标识当客户姓名，避免区分不出自己造的数据 */
    private String uniqueTag() {
        return "IT" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBody(String customerName, String remark) {
        return """
                {"customerName":"%s","customerPhone":"13800138000","totalAmount":199.90,"remark":"%s"}
                """.formatted(customerName, remark);
    }

    /** MockHttpServletResponse#getContentAsString 声明了受检异常，直接按字节解码更省事 */
    private String bodyText(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private OrderResponse createOrder(String customerName) {
        MvcTestResult result = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(customerName, "集成测试"))
                .exchange();
        result.assertThat().hasStatus(HttpStatus.CREATED);
        return jsonMapper.readValue(bodyText(result), OrderResponse.class);
    }

    @Test
    @DisplayName("创建订单：201 + Location，主键回填，版本号被填充为 0")
    void createReturns201WithVersion() {
        MvcTestResult result = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(uniqueTag(), "集成测试"))
                .exchange();

        AbstractJsonContentAssert<?> body = result.assertThat()
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.orderNo").asString().startsWith("ORD");
        body.extractingPath("$.status").asString().isEqualTo("CREATED");
        body.extractingPath("$.statusLabel").asString().isEqualTo("已创建");
        body.extractingPath("$.customerPhone").asString().isEqualTo("138****8000");
        // 这一条是回归测试：version 曾经因为漏标 @TableField(fill = FieldFill.INSERT) 而缺席，
        // 导致创建接口的响应里没有版本号，客户端拿不到就没法紧接着做一次带乐观锁的更新
        body.extractingPath("$.version").asNumber().isEqualTo(0);

        OrderResponse created = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        // MockMvc 环境下没有真实主机名，Location 形如 http://localhost/api/orders/{id}
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/orders/" + created.id());
        assertThat(created.totalAmount()).isEqualByComparingTo(new BigDecimal("199.90"));
    }

    @Test
    @DisplayName("创建订单：返回的时间戳与落库值完全一致，精度不带纳秒")
    void createdTimestampsMatchPersistedRow() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult fetched = mockMvc.get().uri("/api/orders/{id}", created.id())
                .exchange();
        fetched.assertThat().hasStatusOk();
        OrderResponse reloaded = jsonMapper.readValue(bodyText(fetched), OrderResponse.class);

        // DATETIME(3) 只存到毫秒。填充时若不做截断，创建接口返回的 createdAt 带纳秒，
        // 立刻重新查询却是毫秒，同一个字段两个值，客户端做等值比较就会踩空
        assertThat(created.createdAt()).isEqualTo(reloaded.createdAt());
        assertThat(created.updatedAt()).isEqualTo(reloaded.updatedAt());
        assertThat(created.createdAt().getNano() % 1_000_000).isZero();
    }

    @Test
    @DisplayName("创建订单：字段非法时返回 400，逐字段错误放在 errors 属性")
    void createRejectsInvalidBody() {
        AbstractJsonContentAssert<?> body = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"","customerPhone":"123","totalAmount":-1}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson();
        body.extractingPath("$.errors").asArray().hasSize(3);
    }

    @Test
    @DisplayName("查询订单：id 不存在返回 404 问题详情")
    void getByIdReturns404WhenAbsent() {
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders/{id}", 99999999L)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson();
        body.extractingPath("$.title").asString().isEqualTo("资源不存在");
        body.extractingPath("$.resourceType").asString().isEqualTo("订单");
        body.extractingPath("$.resourceId").asNumber().isEqualTo(99999999);
    }

    @Test
    @DisplayName("分页查询：关键字按客户姓名过滤，总数与总页数正确")
    void pageFiltersByCustomerName() {
        String tag = uniqueTag();
        createOrder(tag);
        createOrder(tag);
        createOrder(tag);

        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
                .param("keyword", tag)
                .param("size", "2")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.total").asNumber().isEqualTo(3);
        body.extractingPath("$.totalPages").asNumber().isEqualTo(2);
        body.extractingPath("$.list").asArray().hasSize(2);
    }

    @Test
    @DisplayName("分页查询：关键字也能命中订单号")
    void pageKeywordMatchesOrderNo() {
        OrderResponse created = createOrder(uniqueTag());

        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
                .param("keyword", created.orderNo())
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.total").asNumber().isEqualTo(1);
        // JSONPath 解析出来的小整数是 Integer，直接拿 Long 比会因为类型不同而失败
        body.extractingPath("$.list[0].id").asNumber().isEqualTo(created.id().intValue());
        body.extractingPath("$.list[0].orderNo").asString().isEqualTo(created.orderNo());
    }

    @Test
    @DisplayName("分页查询：size 超过上限时在参数校验层被拦下")
    void pageRejectsSizeOverLimit() {
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
                .param("size", "1000")
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson();
        body.extractingPath("$.errors").asArray().isNotEmpty();
    }

    @Test
    @DisplayName("分页查询：状态枚举非法返回 400 而不是 500")
    void pageRejectsUnknownStatus() {
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
                .param("status", "NOT_A_STATUS")
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson();
        body.extractingPath("$.title").asString().isNotBlank();
    }

    @Test
    @DisplayName("排序：白名单外的字段回退到默认字段，不抛异常")
    void unknownSortFieldFallsBackToDefault() {
        String tag = uniqueTag();
        createOrder(tag);

        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
                .param("keyword", tag)
                .param("sort", "notAField,asc")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.total").asNumber().isEqualTo(1);
    }

    @Test
    @DisplayName("排序：方向非法返回 422，而不是默默按默认方向排")
    void invalidSortDirectionReturns422() {
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
                .param("sort", "createdAt,sideways")
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson();
        body.extractingPath("$.detail").asString().contains("asc");
    }

    @Test
    @DisplayName("更新订单：带上正确版本号，成功后版本号自增")
    void updateIncrementsVersion() {
        OrderResponse created = createOrder(uniqueTag());
        assertThat(created.version()).isZero();

        MvcTestResult result = mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00,"remark":"改过了","version":0}
                        """)
                .exchange();
        result.assertThat().hasStatusOk();

        OrderResponse updated = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.orderNo()).isEqualTo(created.orderNo());
        assertThat(updated.customerName()).isEqualTo("李四");
    }

    @Test
    @DisplayName("更新订单：版本号过期时影响 0 行，翻译成 409")
    void updateRejectsStaleVersion() {
        OrderResponse created = createOrder(uniqueTag());

        // 先用版本号 0 更新一次，数据库里的版本变成 1
        mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00,"version":0}
                        """)
                .exchange()
                .assertThat()
                .hasStatusOk();

        // 再拿已经过期的版本号 0 更新，乐观锁应当拦下来
        AbstractJsonContentAssert<?> body = mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"王五","customerPhone":"13700137000","totalAmount":66.00,"version":0}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson();
        body.extractingPath("$.title").asString().isEqualTo("业务规则不满足");
    }

    @Test
    @DisplayName("更新订单：remark 传 null 会把列真正清空，而不是被当成未提交")
    void updateClearsRemark() {
        OrderResponse created = createOrder(uniqueTag());
        assertThat(created.remark()).isEqualTo("集成测试");

        MvcTestResult result = mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00,"remark":null}
                        """)
                .exchange();
        result.assertThat().hasStatusOk();

        OrderResponse updated = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(updated.remark()).isNull();

        // 响应体里 remark 缺席也可能只是被非空序列化配置隐藏了，所以直接查库确认
        Order row = orderMapper.selectById(created.id());
        assertThat(row.getRemark()).isNull();
    }

    @Test
    @DisplayName("状态流转：CREATED 不能直接跳到 COMPLETED，返回 422")
    void transferStatusRejectsIllegalTransition() {
        OrderResponse created = createOrder(uniqueTag());

        AbstractJsonContentAssert<?> body = mockMvc.patch().uri("/api/orders/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"COMPLETED"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson();
        body.extractingPath("$.detail").asString().contains("不允许");
    }

    @Test
    @DisplayName("状态流转：取消后订单进入终态，不允许再修改")
    void terminalOrderIsNotModifiable() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult cancelled = mockMvc.patch().uri("/api/orders/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"CANCELLED"}
                        """)
                .exchange();
        cancelled.assertThat().hasStatusOk();

        mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"王五","customerPhone":"13700137000","totalAmount":10.00}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    @DisplayName("删除订单：逻辑删除后普通查询查不到，回收站查得到")
    void deleteIsLogicalAndAppearsInRecycleBin() {
        OrderResponse created = createOrder(uniqueTag());

        mockMvc.delete().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NO_CONTENT);

        // 框架生成的查询自动带上 deleted = 0，所以查不到了
        mockMvc.get().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND);

        // 数据行还在表里，只是被标记了；手写 SQL 不追加 deleted = 0，因此能查到
        AbstractJsonContentAssert<?> recycleBin = mockMvc.get().uri("/api/orders/deleted")
                .param("limit", "100")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        recycleBin.extractingPath("$[?(@.id == %d)]".formatted(created.id()))
                .asArray().hasSize(1);

        Order row = orderMapper.selectById(created.id());
        assertThat(row).as("逻辑删除后 selectById 也不该再返回它").isNull();
    }

    @Test
    @DisplayName("删除订单：重复删除返回 404")
    void deleteTwiceReturns404() {
        OrderResponse created = createOrder(uniqueTag());

        mockMvc.delete().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NO_CONTENT);
        mockMvc.delete().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("聚合统计：SQL 侧的计数与求和，和逐行取出来在内存里算的结果一致")
    void statsAgreeWithRowByRowSum() {
        List<OrderStatusStat> stats = orderMapper.selectStatusStats();
        List<Order> rows = orderMapper.selectList(null);

        assertThat(stats).isNotEmpty();
        assertThat(stats).extracting(OrderStatusStat::getStatusLabel).doesNotContainNull();

        long countedInSql = stats.stream().mapToLong(OrderStatusStat::getOrderCount).sum();
        BigDecimal summedInSql = stats.stream()
                .map(OrderStatusStat::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(countedInSql).isEqualTo(rows.size());
        BigDecimal summedInJava = rows.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summedInSql).isEqualByComparingTo(summedInJava);
    }

    @Test
    @DisplayName("JSON 列：明细写入后能原样读回，靠的是 autoResultMap 与 Jackson3TypeHandler")
    void itemsRoundTripThroughJsonColumn() {
        MvcTestResult result = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"%s","customerPhone":"13800138000","totalAmount":349.50,
                         "items":[{"name":"机械键盘","quantity":1,"price":299.50},
                                  {"name":"腕托","quantity":2,"price":25.00}]}
                        """.formatted(uniqueTag()))
                .exchange();
        result.assertThat().hasStatus(HttpStatus.CREATED);
        OrderResponse created = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(created.items()).hasSize(2);
        assertThat(created.items().get(0).name()).isEqualTo("机械键盘");

        // 再查一次。写入走的是实体上的 typeHandler，读取要靠 autoResultMap 生成的 resultMap，
        // 少配任何一个，这里拿到的都是 null
        MvcTestResult fetched = mockMvc.get().uri("/api/orders/{id}", created.id()).exchange();
        OrderResponse reloaded = jsonMapper.readValue(bodyText(fetched), OrderResponse.class);
        assertThat(reloaded.items()).hasSize(2);
        assertThat(reloaded.items().get(1).quantity()).isEqualTo(2);
        assertThat(reloaded.items().get(1).price()).isEqualByComparingTo(new BigDecimal("25.00"));

        // 库里存的确实是一段 JSON 文本，不是一个关联表
        Order row = orderMapper.selectById(created.id());
        assertThat(row.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("JSON 列：手写 XML 查询要用 resultMap 才能应用 typeHandler")
    void recycleBinMapsJsonColumnThroughResultMap() {
        MvcTestResult created = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"%s","customerPhone":"13800138000","totalAmount":100.00,
                         "items":[{"name":"鼠标垫","quantity":1,"price":100.00}]}
                        """.formatted(uniqueTag()))
                .exchange();
        created.assertThat().hasStatus(HttpStatus.CREATED);
        Long id = jsonMapper.readValue(bodyText(created), OrderResponse.class).id();

        mockMvc.delete().uri("/api/orders/{id}", id).exchange()
                .assertThat().hasStatus(HttpStatus.NO_CONTENT);

        // selectDeleted 是手写 SQL，走的是 XML 里那个 resultMap。
        // 如果换成 resultType，items 这一列的转换会失败
        Order deleted = orderMapper.selectDeleted(100).stream()
                .filter(order -> id.equals(order.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(deleted.getItems()).hasSize(1);
        assertThat(deleted.getItems().get(0).name()).isEqualTo("鼠标垫");
    }

    @Test
    @DisplayName("JSON 列：明细里单个商品的校验注解要生效，靠的是列表上的 @Valid")
    void nestedItemValidationIsApplied() {
        AbstractJsonContentAssert<?> body = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"熊大","customerPhone":"13800138000","totalAmount":10.00,
                         "items":[{"name":"","quantity":0,"price":0}]}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson();
        body.extractingPath("$.errors").asArray().hasSize(3);
        body.extractingPath("$.errors[0].field").asString().startsWith("items[0].");
    }

    @Test
    @DisplayName("请求体不是合法 JSON 时返回 400，并给出可读提示")
    void malformedJsonReturns400() {
        AbstractJsonContentAssert<?> body = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":")
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson();
        body.extractingPath("$.title").asString().isEqualTo("请求体格式错误");
    }

    @Test
    @DisplayName("未映射的 HTTP 方法由框架返回 405 问题详情")
    void unsupportedMethodReturns405() {
        mockMvc.patch().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    @DisplayName("错误响应是标准的 problem+json 结构")
    @SuppressWarnings("unchecked")
    void errorResponseIsProblemJson() {
        MvcTestResult result = mockMvc.get().uri("/api/orders/{id}", 88888888L).exchange();
        Map<String, Object> problem = jsonMapper.readValue(bodyText(result), Map.class);

        assertThat(problem).containsKeys("type", "title", "status", "detail", "instance");
        assertThat(problem.get("status")).isEqualTo(404);
        assertThat(problem.get("instance")).isEqualTo("/api/orders/88888888");
    }
}
