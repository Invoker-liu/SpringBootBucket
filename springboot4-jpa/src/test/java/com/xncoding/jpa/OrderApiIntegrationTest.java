package com.xncoding.jpa;

import com.xncoding.jpa.domain.Order;
import com.xncoding.jpa.domain.OrderStatus;
import com.xncoding.jpa.dto.OrderResponse;
import com.xncoding.jpa.repository.OrderRepository;
import jakarta.persistence.EntityManager;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单接口的端到端集成测试，走完整的 Web 层 + 事务 + 真实 MySQL。
 * <p>
 * 与仓储切片测试的分工：切片测试只管"SQL 对不对"，这里管"整条链路对不对"——
 * 参数校验、事务边界、懒加载有没有在视图层炸、问题详情响应结构，
 * 这些都要真的起一次上下文才说得清。
 * <p>
 * 类上加 {@code @Transactional}，每个用例结束后回滚。这里有个本篇特有的细节值得留意：
 * {@code @Transactional} 和 {@code @SoftDelete} 一起用时，软删除标记也会被回滚，
 * 所以用例之间不会互相污染，测试可以反复跑。
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

    /** 注入仓储是为了在少数用例里绕过 HTTP 层，直接核对数据库里的值 */
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private DataSource dataSource;

    /** 少数几处要绕开 Hibernate 的软删除过滤、直接问数据库的断言会用到 */
    @Autowired
    private EntityManager entityManager;

    @BeforeAll
    void checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            Assumptions.assumeTrue(connection.isValid(3), "MySQL 连接不可用，跳过接口集成测试");
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "MySQL 连接不可用（" + ex.getMessage() + "），跳过接口集成测试");
        }
    }

    private String uniqueTag() {
        return "IT" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBody(String customerName) {
        return """
                {"customerName":"%s","customerPhone":"13800138000","totalAmount":299.50,"remark":"集成测试",
                 "items":[{"productName":"机械键盘","price":299.50,"quantity":1},
                          {"productName":"腕托","price":25.00,"quantity":2}]}
                """.formatted(customerName);
    }

    /** MockHttpServletResponse#getContentAsString 声明了受检异常，直接按字节解码更省事 */
    private String bodyText(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private OrderResponse createOrder(String customerName) {
        MvcTestResult result = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(customerName))
                .exchange();
        result.assertThat().hasStatus(HttpStatus.CREATED);
        return jsonMapper.readValue(bodyText(result), OrderResponse.class);
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    @Test
    @DisplayName("创建订单：201 + Location，主键与版本号都被回填")
    void createReturns201WithVersion() {
        MvcTestResult result = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(uniqueTag()))
                .exchange();

        AbstractJsonContentAssert<?> body = result.assertThat()
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        body.extractingPath("$.orderNo").asString().startsWith("ORD");
        body.extractingPath("$.status").asString().isEqualTo("CREATED");
        body.extractingPath("$.statusLabel").asString().isEqualTo("已创建");
        body.extractingPath("$.customerPhone").asString().isEqualTo("138****8000");
        // 这一条是回归测试：version 没被填上的话，客户端就拿不到版本号，
        // 也就没法紧接着做一次带乐观锁的更新
        body.extractingPath("$.version").asNumber().isEqualTo(0);
        body.extractingPath("$.items").asArray().hasSize(2);

        OrderResponse created = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/orders/" + created.id());
        assertThat(created.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("创建订单：明细合计与手工相加一致，金额精度不失真")
    void createKeepsDecimalPrecision() {
        OrderResponse created = createOrder(uniqueTag());

        BigDecimal manual = created.items().stream()
                .map(item -> item.price().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(manual).isEqualByComparingTo("349.50");
        // 明细里的 amount 是 getAmount() 算出来的，必须逐项对得上
        assertThat(created.items()).allSatisfy(item ->
                assertThat(item.amount()).isEqualByComparingTo(
                        item.price().multiply(BigDecimal.valueOf(item.quantity()))));
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
    @DisplayName("创建订单：明细里单个商品的校验注解要生效，靠的是列表上的 @Valid")
    void nestedItemValidationIsApplied() {
        AbstractJsonContentAssert<?> body = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"熊大","customerPhone":"13800138000","totalAmount":10.00,
                         "items":[{"productName":"","price":0,"quantity":0}]}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson();
        body.extractingPath("$.errors").asArray().hasSize(3);
        body.extractingPath("$.errors[0].field").asString().startsWith("items[0].");
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

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
    @DisplayName("查询订单：明细在事务内被转成 DTO，open-in-view 关掉也不炸")
    void getByIdReturnsItemsDespiteOpenInViewDisabled() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult result = mockMvc.get().uri("/api/orders/{id}", created.id()).exchange();
        result.assertThat().hasStatusOk();

        OrderResponse reloaded = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        // 这一条是回归测试。open-in-view 关掉之后，实体转 DTO 必须发生在事务方法内部，
        // 一旦挪到 Controller 里（也就是出了事务），getItems() 就会抛
        // LazyInitializationException，接口直接 500
        assertThat(reloaded.items()).hasSize(2);
        assertThat(reloaded.items().get(0).productName()).isEqualTo("机械键盘");
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
        // 分页响应刻意不带 Spring Data 的 pageable/sort 嵌套对象，页码从 0 开始
        body.extractingPath("$.page").asNumber().isEqualTo(0);
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
        mockMvc.get().uri("/api/orders")
                .param("status", "NOT_A_STATUS")
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("排序：用数据库列名会被白名单挡下，回退到默认字段而不是抛异常")
    void sortByColumnNameFallsBackToDefault() {
        String tag = uniqueTag();
        createOrder(tag);

        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders")
                .param("keyword", tag)
                .param("sort", "created_at,desc")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        // 白名单里没有 created_at，所以回退了；换成 Sort.by 直接抛异常的那条路在切片测试里
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
    @DisplayName("聚合统计：JPQL 构造器表达式映射的 record 能正常序列化")
    void statsReturnsRecordWithLabel() {
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders/stats")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$[0].statusLabel").asString().isNotBlank();
        body.extractingPath("$[0].count").asNumber().isNotNull();
        body.extractingPath("$[0].totalAmount").asNumber().isNotNull();
    }

    @Test
    @DisplayName("接口投影：摘要列表每行只有四个字段，不该出现 remark 和 items")
    void summaryProjectionHasOnlyFourFields() {
        MvcTestResult result = mockMvc.get().uri("/api/orders/summary")
                .param("status", "CREATED")
                .exchange();
        result.assertThat().hasStatusOk();

        // 用 Map 来接，才能断言"哪些键不存在"——投影的价值就在于少查的那几列
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<Map<String, Object>>) jsonMapper.readValue(bodyText(result), java.util.List.class);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0)).containsOnlyKeys("id", "orderNo", "totalAmount", "status");
    }

    @Test
    @DisplayName("原生 SQL 统计：手写的 deleted = 0 生效，且状态由入参决定")
    void countNativeQueryUsesRequestedStatus() {
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders/count")
                .param("status", "COMPLETED")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.status").asString().isEqualTo("COMPLETED");
        body.extractingPath("$.count").asNumber().isNotNull();
    }

    @Test
    @DisplayName("JPQL 条件查询：金额下限由参数决定，返回结果全部满足条件")
    void expensiveQueryRespectsMinAmount() {
        MvcTestResult result = mockMvc.get().uri("/api/orders/expensive")
                .param("minAmount", "200")
                .exchange();
        result.assertThat().hasStatusOk();

        var rows = jsonMapper.readValue(bodyText(result), OrderResponse[].class);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row ->
                assertThat(row.totalAmount()).isGreaterThanOrEqualTo(new BigDecimal("200")));
    }

    // ------------------------------------------------------------------
    // 更新与状态流转
    // ------------------------------------------------------------------

    @Test
    @DisplayName("更新订单：带上正确版本号，成功后版本号自增")
    void updateIncrementsVersion() {
        OrderResponse created = createOrder(uniqueTag());
        assertThat(created.version()).isZero();

        MvcTestResult result = mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00,
                         "remark":"改过了","version":0,
                         "items":[{"productName":"鼠标垫","price":88.00,"quantity":1}]}
                        """)
                .exchange();
        result.assertThat().hasStatusOk();

        OrderResponse updated = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.orderNo()).isEqualTo(created.orderNo());
        assertThat(updated.customerName()).isEqualTo("李四");
        // PUT 是整体替换，明细也跟着换掉了
        assertThat(updated.items()).hasSize(1);
        assertThat(updated.items().get(0).productName()).isEqualTo("鼠标垫");
    }

    @Test
    @DisplayName("更新订单：版本号过期时被显式拦下，返回 409")
    void updateRejectsStaleVersion() {
        OrderResponse created = createOrder(uniqueTag());

        // 先用版本号 0 更新一次，库里的版本变成 1
        mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00,"version":0}
                        """)
                .exchange()
                .assertThat()
                .hasStatusOk();

        // 再拿已经过期的版本号 0 更新，服务层的显式比对应当把它拦下来
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
        body.extractingPath("$.detail").asString().contains("当前版本 1");
    }

    @Test
    @DisplayName("更新订单：不传版本号就退化成普通更新，不做并发校验")
    void updateWithoutVersionSkipsOptimisticCheck() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult result = mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00}
                        """)
                .exchange();
        result.assertThat().hasStatusOk();

        // 版本号照常自增，只是没有做"客户端带来的版本是否过期"这一步比对
        OrderResponse updated = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("状态流转：CREATED → PAID 合法，版本号继续自增")
    void changeStatusTransfersLegally() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult result = mockMvc.patch().uri("/api/orders/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"PAID"}
                        """)
                .exchange();
        result.assertThat().hasStatusOk();

        OrderResponse paid = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(paid.statusLabel()).isEqualTo("已支付");
    }

    @Test
    @DisplayName("状态流转：PAID 不能退回 CREATED，返回 422")
    void changeStatusRejectsIllegalTransition() {
        OrderResponse created = createOrder(uniqueTag());

        mockMvc.patch().uri("/api/orders/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"PAID"}
                        """)
                .exchange()
                .assertThat()
                .hasStatusOk();

        AbstractJsonContentAssert<?> body = mockMvc.patch().uri("/api/orders/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"CREATED"}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson();
        body.extractingPath("$.detail").asString().contains("不允许");
    }

    @Test
    @DisplayName("状态流转：进入终态后连普通更新都不允许")
    void terminalOrderIsNotModifiable() {
        OrderResponse created = createOrder(uniqueTag());

        mockMvc.patch().uri("/api/orders/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"CANCELLED"}
                        """)
                .exchange()
                .assertThat()
                .hasStatusOk();

        mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"王五","customerPhone":"13700137000","totalAmount":10.00}
                        """)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除订单：软删除后接口查不到，但数据行还在库里，deleted 被置成 1")
    void deleteIsSoftAndRowRemains() {
        OrderResponse created = createOrder(uniqueTag());

        mockMvc.delete().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NO_CONTENT);

        // 框架生成的查询自动带上 deleted = false
        mockMvc.get().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND);

        // 仓储也查不到了——findById 走的还是 Hibernate 生成的 SQL，同样被过滤
        assertThat(orderRepository.findById(created.id())).isEmpty();

        // 但没有真删。绕开 Hibernate 直接问数据库：那一行还在，deleted 是 1。
        // 这一条正是 @SoftDelete 和"真的 DELETE"的分水岭，也是原生 SQL 必须自己写
        // deleted = 0 的原因——框架的过滤只管它自己生成的语句。
        Object physical = entityManager.createNativeQuery(
                        "select count(*) from t_order where id = :id and deleted = 1")
                .setParameter("id", created.id())
                .getSingleResult();
        assertThat(((Number) physical).longValue()).as("软删除后物理行仍在，且 deleted 被置为 1").isEqualTo(1L);
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

    // ------------------------------------------------------------------
    // 框架层面的响应约定
    // ------------------------------------------------------------------

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
