package com.xncoding.mongo;

import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderStatus;
import com.xncoding.mongo.dto.OrderResponse;
import com.xncoding.mongo.repository.OrderRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单接口的端到端集成测试，走完整的 Web 层 + 真实 MongoDB。
 * <p>
 * 与切片测试的分工：切片测试只管"数据访问对不对"，这里管"整条链路对不对"——
 * 参数校验、状态机、乐观锁的两套保护机制、聚合结果的序列化、问题详情响应结构，
 * 这些都要真的起一次上下文才说得清。
 * <p>
 * <b>这里没有类级 {@code @Transactional}。</b>上一篇有，靠它让每个用例结束后回滚；
 * 单机 MongoDB 不支持多文档事务，这条路走不通，只能每个用例开始前手动清空集合
 * （见 {@link #cleanUp()}）。这是"没有事务"这件事最直接的体感：
 * 连测试都得多写一行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderApiIntegrationTest {

    @Autowired
    private MockMvcTester mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    /** 注入仓储是为了在少数用例里绕过 HTTP 层，直接核对库里的值 */
    @Autowired
    private OrderRepository orderRepository;

    /** 核对"文档是不是真的没了"这类物理事实，绕开所有映射逻辑 */
    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeAll
    void checkDatabase() {
        try {
            mongoTemplate.executeCommand("{ ping: 1 }");
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "MongoDB 不可用（" + ex.getMessage() + "），跳过接口集成测试");
        }
    }

    /** 测试跑在独立的 springboot4_mongo_test 库上，清空是安全的 */
    @BeforeEach
    void cleanUp() {
        orderRepository.deleteAll();
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
    @DisplayName("创建订单：201 + Location，ObjectId 与版本号都被回填")
    void createReturns201WithVersion() {
        MvcTestResult result = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(uniqueTag()))
                .exchange();

        AbstractJsonContentAssert<?> body = result.assertThat()
                .hasStatus(HttpStatus.CREATED)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .bodyJson();
        // 主键是 ObjectId 的 24 位十六进制字符串，不是自增数字
        body.extractingPath("$.id").asString().matches("[0-9a-f]{24}");
        body.extractingPath("$.orderNo").asString().startsWith("ORD");
        body.extractingPath("$.status").asString().isEqualTo("CREATED");
        body.extractingPath("$.statusLabel").asString().isEqualTo("已创建");
        body.extractingPath("$.customerPhone").asString().isEqualTo("138****8000");
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
        // 明细里的 amount 是 getAmount() 现算的（标了 @Transient，不在文档里），必须逐项对得上
        assertThat(created.items()).allSatisfy(item ->
                assertThat(item.amount()).isEqualByComparingTo(
                        item.price().multiply(BigDecimal.valueOf(item.quantity()))));
    }

    @Test
    @DisplayName("创建订单：amount 标了 @Transient，文档里不该有它")
    void transientAmountIsNotPersisted() {
        createOrder(uniqueTag());

        var raw = mongoTemplate.getCollection("orders").find().first();
        assertThat(raw).isNotNull();
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) raw.get("items");
        assertThat(items).isNotEmpty();
        // 少写一个 @Transient，这里就会多出一个 amount 字段，
        // 而且它永远不会被更新，最后变成一份对不上的幽灵数据
        assertThat(items.get(0)).doesNotContainKey("amount");
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
        // 一个格式合法但库里没有的 ObjectId
        String absentId = "0".repeat(24);
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders/{id}", absentId)
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson();
        body.extractingPath("$.title").asString().isEqualTo("资源不存在");
        body.extractingPath("$.resourceType").asString().isEqualTo("订单");
        body.extractingPath("$.resourceId").asString().isEqualTo(absentId);
    }

    @Test
    @DisplayName("查询订单：id 不是合法 ObjectId 时返回 404，而不是 500")
    void malformedIdReturns404NotServerError() {
        // 这条是回归测试，也是本篇一个很实际的坑：
        // ObjectId 是 24 位十六进制，客户端拼一个 "abc" 过来，
        // 驱动在把字符串转成 ObjectId 时就会抛 ConversionFailedException。
        // 不在服务层挡一道，它一路冒到全局异常处理器变成 500 ——
        // 明明是 URL 写错，却报服务器内部错误。
        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders/{id}", "abc")
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson();
        body.extractingPath("$.title").asString().isEqualTo("资源不存在");
    }

    @Test
    @DisplayName("查询订单：内嵌明细随文档一起返回")
    void getByIdReturnsEmbeddedItems() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult result = mockMvc.get().uri("/api/orders/{id}", created.id()).exchange();
        result.assertThat().hasStatusOk();

        OrderResponse reloaded = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        // 和 JPA 那篇不同，这里不需要保证"在事务里转 DTO"：
        // 内嵌数组是文档的一部分，读出来就在内存里，没有懒加载这回事。
        assertThat(reloaded.items()).hasSize(2);
        assertThat(reloaded.items().get(0).productName()).isEqualTo("机械键盘");
    }

    @Test
    @DisplayName("分页查询：关键字按订单号和客户名过滤，总数与总页数正确")
    void pageFiltersByKeyword() {
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
    @DisplayName("排序：存储名 created_at 与属性名 createdAt 都能工作（和 JPA 那篇的差别）")
    void sortAcceptsBothPropertyNameAndStoredName() {
        String tag = uniqueTag();
        createOrder(tag);
        createOrder(tag);

        // 属性名
        mockMvc.get().uri("/api/orders")
                .param("keyword", tag)
                .param("sort", "createdAt,desc")
                .exchange()
                .assertThat()
                .hasStatusOk();

        // 存储名。这一条在上一篇（JPA）会直接抛 PropertyReferenceException、
        // 报 "No property 'created' found"，因为下划线在 Spring Data 里是嵌套属性的分隔符。
        // 而 MongoDB 这边 Sort 的字段名会原样进 BSON 的 sort 段，
        // 而 created_at 恰好就是文档里真实的字段名，所以它真的能排序。
        // 接口层用白名单统一口径，是为了不把"两种写法都能跑"这个偶然当成契约。
        mockMvc.get().uri("/api/orders")
                .param("size", "1")
                .param("sort", "created_at,desc")
                .exchange()
                .assertThat()
                .hasStatusOk();
    }

    @Test
    @DisplayName("聚合统计：注解版与模板版结果完全一致")
    void bothAggregationStylesAgree() {
        String tag = uniqueTag();
        createOrder(tag);
        createOrder(tag);

        MvcTestResult annotationResult = mockMvc.get().uri("/api/orders/stats").exchange();
        annotationResult.assertThat().hasStatusOk();
        MvcTestResult templateResult = mockMvc.get().uri("/api/orders/stats/by-template").exchange();
        templateResult.assertThat().hasStatusOk();

        List<Map<String, Object>> fromAnnotation = jsonMapper.readValue(
                bodyText(annotationResult), List.class);
        List<Map<String, Object>> fromTemplate = jsonMapper.readValue(
                bodyText(templateResult), List.class);

        assertThat(fromAnnotation).isNotEmpty();
        // 同一份数据、两条不同的管道实现，输出必须逐条对得上
        assertThat(fromTemplate).isEqualTo(fromAnnotation);
    }

    @Test
    @DisplayName("聚合统计：group key 是 _id，靠 @Field(\"_id\") 映射到 status 上")
    void aggregationGroupKeyIsMapped() {
        createOrder(uniqueTag());

        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders/stats")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        // 响应里应该是 status，不该出现 _id
        body.extractingPath("$[0].status").asString().isNotBlank();
        body.extractingPath("$[0].statusLabel").asString().isNotBlank();
        body.extractingPath("$[0].count").asNumber().isNotNull();
    }

    @Test
    @DisplayName("$unwind 排行：把内嵌明细摊平之后按商品汇总")
    void topProductsUnwindsEmbeddedItems() {
        String tag = uniqueTag();
        createOrder(tag);
        createOrder(tag);

        MvcTestResult result = mockMvc.get().uri("/api/orders/top-products")
                .param("limit", "5")
                .exchange();
        result.assertThat().hasStatusOk();

        List<Map<String, Object>> rows = jsonMapper.readValue(bodyText(result), List.class);
        assertThat(rows).isNotEmpty();
        // 两条订单，每条各有一个"机械键盘"，$unwind 之后数量合计是 2
        Map<String, Object> keyboard = rows.stream()
                .filter(row -> "机械键盘".equals(row.get("productName")))
                .findFirst()
                .orElseThrow();
        assertThat(((Number) keyboard.get("totalQuantity")).longValue()).isEqualTo(2);
        // 销售额是 $multiply 现算的，因为 amount 标了 @Transient 不在文档里
        assertThat(new BigDecimal(keyboard.get("salesAmount").toString()))
                .isEqualByComparingTo("599.00");
    }

    @Test
    @DisplayName("接口投影：摘要列表每行只有四个字段")
    void summaryProjectionHasOnlyFourFields() {
        createOrder(uniqueTag());

        MvcTestResult result = mockMvc.get().uri("/api/orders/summary")
                .param("status", "CREATED")
                .exchange();
        result.assertThat().hasStatusOk();

        var rows = (java.util.List<Map<String, Object>>) jsonMapper.readValue(bodyText(result), java.util.List.class);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0)).containsOnlyKeys("id", "orderNo", "customerName", "status");
    }

    @Test
    @DisplayName("反例：手写 fields 投影出去，没查的字段全是 null")
    void fieldsProjectionLeaksNullsIntoResponse() {
        createOrder(uniqueTag());

        MvcTestResult result = mockMvc.get().uri("/api/orders/summary/projection")
                .param("status", "CREATED")
                .exchange();
        result.assertThat().hasStatusOk();

        var rows = (java.util.List<Map<String, Object>>) jsonMapper.readValue(bodyText(result), java.util.List.class);
        assertThat(rows).isNotEmpty();
        // jackson 配了 non_null，所以 null 字段直接不出现在 JSON 里。
        // 结果是"少了一半字段"，客户端拿到的数据是不完整的 ——
        // 这个问题不会报错，只会在联调时以"字段怎么没了"的形式出现。
        assertThat(rows.get(0))
                .containsKeys("id", "orderNo", "customerName", "status", "statusLabel")
                .doesNotContainKeys("remark", "version", "createdAt", "updatedAt");
        // items 是个例外：它的字段初始值是空列表，没查到就是空数组而不是 null，
        // 所以它会照常出现在响应里 —— 一个"看起来正常但其实是空的"字段，
        // 比缺字段更容易骗过联调的人
        assertThat(rows.get(0).get("items")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("条件计数：状态由入参决定")
    void countUsesRequestedStatus() {
        createOrder(uniqueTag());

        AbstractJsonContentAssert<?> body = mockMvc.get().uri("/api/orders/count")
                .param("status", "CREATED")
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.status").asString().isEqualTo("CREATED");
        body.extractingPath("$.count").asNumber().isNotNull();
    }

    @Test
    @DisplayName("distinct：服务端去重，返回出现过的状态")
    void distinctStatuses() {
        createOrder(uniqueTag());

        MvcTestResult result = mockMvc.get().uri("/api/orders/statuses").exchange();
        result.assertThat().hasStatusOk();
        assertThat(bodyText(result)).contains("CREATED");
    }

    @Test
    @DisplayName("派生查询：金额下限由参数决定，返回结果全部满足条件")
    void expensiveQueryRespectsMinAmount() {
        createOrder(uniqueTag());

        MvcTestResult result = mockMvc.get().uri("/api/orders/expensive")
                .param("minAmount", "200")
                .exchange();
        result.assertThat().hasStatusOk();

        var rows = jsonMapper.readValue(bodyText(result), OrderResponse[].class);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row ->
                assertThat(row.totalAmount()).isGreaterThanOrEqualTo(new BigDecimal("200")));
    }

    @Test
    @DisplayName("内嵌数组匹配：按商品名反查订单")
    void findOrdersContainingProduct() {
        createOrder(uniqueTag());

        MvcTestResult result = mockMvc.get().uri("/api/orders/by-item")
                .param("productName", "机械键盘")
                .exchange();
        result.assertThat().hasStatusOk();

        var rows = jsonMapper.readValue(bodyText(result), OrderResponse[].class);
        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row ->
                assertThat(row.items()).extracting("productName").contains("机械键盘"));
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
        assertThat(updated.items()).hasSize(1);
        assertThat(updated.items().get(0).productName()).isEqualTo("鼠标垫");
    }

    @Test
    @DisplayName("更新订单：版本号过期时被显式拦下，返回 409")
    void updateRejectsStaleVersion() {
        OrderResponse created = createOrder(uniqueTag());

        mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00,"version":0}
                        """)
                .exchange()
                .assertThat()
                .hasStatusOk();

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
        // transferStatus 里手动 inc 了版本号，客户端下次 PUT 才不会莫名撞 409
        assertThat(paid.version()).isEqualTo(1);
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
    // 明细的原子增删
    // ------------------------------------------------------------------

    @Test
    @DisplayName("$push：追加明细后数组变长，订单其余字段不动")
    void addItemPushesIntoArray() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult result = mockMvc.post().uri("/api/orders/{id}/items", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productName":"鼠标垫","price":88.00,"quantity":1}
                        """)
                .exchange();
        result.assertThat().hasStatusOk();

        OrderResponse updated = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        assertThat(updated.items()).hasSize(3);
        assertThat(updated.items()).extracting("productName").contains("鼠标垫");
        assertThat(updated.orderNo()).isEqualTo(created.orderNo());
    }

    @Test
    @DisplayName("$pull：按商品名移除明细，同名商品一起被移除")
    void removeItemPullsFromArray() {
        OrderResponse created = createOrder(uniqueTag());

        MvcTestResult result = mockMvc.delete().uri("/api/orders/{id}/items", created.id())
                .param("productName", "腕托")
                .exchange();
        result.assertThat().hasStatusOk();

        @SuppressWarnings("unchecked")
        Map<String, Object> removed = jsonMapper.readValue(bodyText(result), Map.class);
        assertThat(((Number) removed.get("removed")).longValue()).isEqualTo(1);

        OrderResponse reloaded = jsonMapper.readValue(
                bodyText(mockMvc.get().uri("/api/orders/{id}", created.id()).exchange()),
                OrderResponse.class);
        assertThat(reloaded.items()).extracting("productName").containsExactly("机械键盘");
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("删除订单：文档真的从集合里消失了（和上一篇的软删除不一样）")
    void deleteRemovesDocumentForReal() {
        OrderResponse created = createOrder(uniqueTag());

        mockMvc.delete().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NO_CONTENT);

        mockMvc.get().uri("/api/orders/{id}", created.id())
                .exchange()
                .assertThat()
                .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(orderRepository.findById(created.id())).isEmpty();

        // 绕开所有映射逻辑直接问集合：文档是真的没了。
        // 上一篇在这一步会查到"行还在，只是 deleted 被置成了 1"；
        // MongoDB 没有 @SoftDelete，deleteById 就是一条 delete 命令。
        long physical = mongoTemplate.getCollection("orders")
                .countDocuments(new org.bson.Document("_id", new org.bson.types.ObjectId(created.id())));
        assertThat(physical).as("文档已被物理删除").isZero();
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
        String absentId = "f".repeat(24);
        MvcTestResult result = mockMvc.get().uri("/api/orders/{id}", absentId).exchange();
        Map<String, Object> problem = jsonMapper.readValue(bodyText(result), Map.class);

        assertThat(problem).containsKeys("type", "title", "status", "detail", "instance");
        assertThat(problem.get("status")).isEqualTo(404);
        assertThat(problem.get("instance")).isEqualTo("/api/orders/" + absentId);
    }

    /** 集合里现在有几条，用于断言"没有多余的写入" */
    private long documentCount() {
        return mongoTemplate.getCollection("orders").countDocuments();
    }

    @Test
    @DisplayName("字段名映射：库里存的是 order_no / created_at，不是 Java 属性名")
    void storedFieldNamesAreSnakeCase() {
        createOrder(uniqueTag());

        var raw = mongoTemplate.getCollection("orders").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.keySet()).contains("order_no", "customer_name", "customer_phone",
                "total_amount", "created_at", "updated_at", "status", "version", "items");
        assertThat(documentCount()).isEqualTo(1);
        // 确认一下 Order 实体确实是按 collection = "orders" 落库的
        assertThat(mongoTemplate.getCollectionName(Order.class)).isEqualTo("orders");
    }
}
