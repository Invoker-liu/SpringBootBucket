# springboot4-jackson3

Spring Boot 4 教程第 23 篇配套工程：Jackson 3 与空安全。

订单接口对外输出 JSON 的规范化：空字段不出现在响应里、金额 null 的三种语义
（省略 / 输出 null / 落成缺省值）、反序列化容错。全部结论实测自
Spring Boot 4.1.1 + Jackson 3（tools.jackson.core:jackson-databind 3.1.5）。

## 运行

```bash
# 需要 JDK 21，端口 18230
mvn spring-boot:run
```

## 测试

```bash
mvn test
```

11 个测试（JacksonNullStrategyTest 9 + GlobalInclusionPropertyTest 2），
起真实 Tomcat 走 HTTP 断言。

## 接口一览

| 方法 | 路径 | 演示点 |
|---|---|---|
| GET | /api/orders/2 | 默认行为：null 字段照常输出 |
| GET | /api/orders/2/non-null | 类级 @JsonInclude(NON_NULL) |
| GET | /api/orders/2/field-include | 字段级 @JsonInclude(NON_NULL) |
| GET | /api/orders/2/record | record DTO 输出（声明序 + null 照常） |
| GET | /api/orders/1/amount | 自定义 ValueSerializer：分转元 |
| POST | /api/orders/parse | AS_EMPTY / primitive null / 元转分 / 别名 |
| POST | /api/orders | 正常下单（同一套容错规则） |
| GET | /api/jackson/defaults | Feature 默认值探针 |

## 实测结论

1. Boot 容器装配的是 `tools.jackson.databind.json.JsonMapper`；
   `FAIL_ON_UNKNOWN_PROPERTIES` 默认 false（未知字段静默忽略），
   `FAIL_ON_NULL_FOR_PRIMITIVES` 默认 true（JSON null 打进 primitive 返回异常），
   `FAIL_ON_EMPTY_BEANS` 默认 false。
2. Jackson 3 默认输出 null 字段（默认 inclusion 仍是 ALWAYS），普通 DTO 字段按字母序输出，
   record 组件按声明序输出。
3. 三种空值策略：类级/字段级 `@JsonInclude(NON_NULL)`（注解在
   com.fasterxml.jackson.annotation）；全局 `spring.jackson.default-property-inclusion=non_null`
   （spring-boot-jackson 的 JacksonProperties，34 条 spring.jackson.* 属性之一），
   开启后普通 DTO 与 record 同时受控。
4. 反序列化方向：`@JsonSetter(nulls = Nulls.AS_EMPTY)` 使 String null 落 ""、Long null 落 0；
   `Nulls` 枚举共 SET/SKIP/FAIL/AS_EMPTY/DEFAULT 五个值。
5. 金额分转元示例：内部一律存分（long），`@JsonSerialize(using = CentsToYuanSerializer.class)`
   对外输出 241.82 这样的元值（BigDecimal scale=2 尾零保留）；
   请求体 `{"amount": 241.82}` 经 ValueDeserializer 落库 24182 分
   （解析用 `JsonParser#getDecimalValue()`，`getNumberValue()` 对小数给的是 Double）。
6. Jackson 3 异常全部非受检：`DatabindException extends JacksonException extends RuntimeException`，
   @RestControllerAdvice 捕获后按 400 problem+json 输出，方法签名无需 throws。
