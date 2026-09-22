package com.xncoding.restful;

import com.xncoding.restful.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.AbstractJsonContentAssert;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单接口的 MVC 测试。
 * <p>
 * 使用 Spring Framework 7 的 {@link MockMvcTester}：请求写法与 MockMvc 一致，
 * 断言换成 AssertJ，失败时能直接看到响应体，不必再手工 {@code andReturn()} 后解析字符串。
 * <p>
 * 注意 Boot 4 的两个破坏性变更：
 * <ol>
 *   <li>{@code @SpringBootTest} <b>不再</b>隐式启用 MockMvc，必须显式标注 {@code @AutoConfigureMockMvc}；</li>
 *   <li>该注解的包路径已从 {@code boot.test.autoconfigure.web.servlet} 迁到
 *       {@code boot.webmvc.test.autoconfigure}——Boot 4 把测试自动配置按模块拆分了。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiMockMvcTest {

    @Autowired
    private MockMvcTester mockMvc;

    /** Boot 4 自动配置的 Jackson 3 JsonMapper，用于在测试中解析响应体 */
    @Autowired
    private JsonMapper jsonMapper;

    /**
     * 每次测试用独立标识作为客户姓名，避免共享的内存仓储让用例之间相互干扰。
     */
    private String uniqueTag() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String createBody(String customerName) {
        return """
                {"customerName":"%s","customerPhone":"13800138000","totalAmount":199.90,"remark":"加急"}
                """.formatted(customerName);
    }

    /**
     * 以 UTF-8 读取响应体字节并转字符串。
     * <p>
     * {@code MockHttpServletResponse#getContentAsString()} 声明了受检的
     * {@code UnsupportedEncodingException}，直接按字节解码可以避开这个无意义的异常声明。
     */
    private String bodyText(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 下单并返回服务端生成的订单视图对象。
     */
    private OrderResponse createOrder(String customerName) {
        MvcTestResult result = mockMvc.post().uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(customerName))
                .exchange();
        result.assertThat().hasStatus(HttpStatus.CREATED);
        return jsonMapper.readValue(bodyText(result), OrderResponse.class);
    }

    @Test
    @DisplayName("创建订单：返回 201、Location 头与订单号，手机号已脱敏")
    void createReturns201WithLocation() {
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

        OrderResponse created = jsonMapper.readValue(bodyText(result), OrderResponse.class);
        // MockMvc 环境下没有真实主机名，Location 形如 http://localhost/api/orders/{id}
        assertThat(result.getResponse().getHeader("Location")).endsWith("/api/orders/" + created.id());
        assertThat(created.totalAmount()).isEqualByComparingTo(new BigDecimal("199.90"));
    }

    @Test
    @DisplayName("创建订单：字段非法时返回 400，逐字段错误统一放在 errors 属性")
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
    @DisplayName("分页查询：关键字过滤与每页条数生效，总页数按总数换算")
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
    }

    @Test
    @DisplayName("分页查询：size 超过上限时在参数校验层就被拦下")
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
    @DisplayName("更新订单：PUT 幂等，重复提交同一请求结果一致")
    void updateIsIdempotent() {
        OrderResponse created = createOrder(uniqueTag());
        String updateBody = """
                {"customerName":"李四","customerPhone":"13900139000","totalAmount":88.00}
                """;

        MvcTestResult first = mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .exchange();
        first.assertThat().hasStatusOk();

        MvcTestResult second = mockMvc.put().uri("/api/orders/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .exchange();
        second.assertThat().hasStatusOk();

        // 订单号不变，说明 PUT 是"整体替换业务字段"而非"重新下单"
        OrderResponse firstBody = jsonMapper.readValue(bodyText(first), OrderResponse.class);
        OrderResponse secondBody = jsonMapper.readValue(bodyText(second), OrderResponse.class);
        assertThat(secondBody.orderNo()).isEqualTo(firstBody.orderNo());
        assertThat(secondBody.customerName()).isEqualTo("李四");
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
    @DisplayName("状态流转：取消成功后订单进入终态，不允许再修改")
    void terminalOrderIsNotModifiable() {
        OrderResponse created = createOrder(uniqueTag());

        AbstractJsonContentAssert<?> body = mockMvc.patch().uri("/api/orders/{id}/status", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"CANCELLED"}
                        """)
                .exchange()
                .assertThat()
                .hasStatusOk()
                .bodyJson();
        body.extractingPath("$.status").asString().isEqualTo("CANCELLED");

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
    @DisplayName("删除订单：首次 204，重复删除 404")
    void deleteReturns204Then404() {
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
    @DisplayName("排序：白名单外的排序字段回退到默认字段，不抛异常")
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
    @DisplayName("排序方向非法时返回 422")
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
