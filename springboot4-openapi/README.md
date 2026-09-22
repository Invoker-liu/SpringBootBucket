# springboot4-openapi

Spring Boot 4 教程第 20 篇示例工程：用 springdoc-openapi 给订单服务生成 OpenAPI 3.1 接口文档，供前端与第三方联调。

## 运行

```bash
# JDK 21，端口 18200
mvn spring-boot:run
```

- swagger-ui 页面：<http://localhost:18200/swagger-ui/index.html>（`/swagger-ui.html` 会 302 过去）
- 全量文档：`GET /v3/api-docs`，输出 `"openapi":"3.1.0"`
- 分组文档：`GET /v3/api-docs/orders`（订单接口）、`GET /v3/api-docs/admin`（订单运维接口）
- 分组下拉数据源：`GET /v3/api-docs/swagger-config`
- 管理接口 HTTP Basic 账号：`admin / admin-2026`（application.yml 的 `spring.security.user.*`）

## 测试

```bash
mvn test
```

18 个用例，6 个测试类：

| 测试类 | 数量 | 覆盖 |
|---|---|---|
| ApiDocsContractTest | 6 | openapi 版本 3.1.0、路径清单、schema 约束映射、枚举内联、复用响应组件、securitySchemes 声明 |
| GroupedApiDocsTest | 3 | 两组文档互不越界、swagger-config 的 urls 与 displayName |
| SwaggerUiPageTest | 2 | `/swagger-ui.html` 302、`/swagger-ui/index.html` 200 |
| ApiDocsToggleTest | 1 | `springdoc.api-docs.enabled=false` 后 `/v3/api-docs` 404 |
| ApiDocsPathRenameTest | 1 | `springdoc.api-docs.path` 改名后新路径 200、默认路径 404 |
| OrderApiSmokeTest | 5 | 建单 201、单号重复 409、校验失败 400、无凭据 401、Basic 凭据 204/404 |

## 实测结论

- Boot 4.1.1 的官方 BOM 不管理 springdoc，版本号必须显式写在 pom 里（本工程用 3.1.1，
  其父 POM 基于 spring-boot-starter-parent 4.1.0）。
- 3.1.1 内置 swagger-core-jakarta 2.2.55、swagger-ui 5.32.14，文档输出 `"openapi":"3.1.0"`。
- 默认路径：`/v3/api-docs` 与 `/swagger-ui.html`（302 到 `/swagger-ui/index.html`）；
  `api-docs.enabled=false` 时文档与 swagger-ui 页面一起 404；`api-docs.path` 改名后
  分组文档跟随新前缀。
- 校验注解直接进 schema：`@NotBlank/@NotNull` 进 required 数组，`@Size(max=32)` 得
  `maxLength:32`（附带 `minLength:0`），`@DecimalMin("0.01")` 得 `minimum:0.01`；
  枚举内联进属性（`"enum":["NEW","PAID","SHIPPED","CANCELLED"]`），不生成独立 schema。
- `swagger-config` 的 `urls[].name` 取 `GroupedOpenApi.displayName`，不是 group 名。
- Spring Security 过滤器链的 401 出口不走 `@RestControllerAdvice`，响应体是 Boot 默认
  error JSON；`components.securitySchemes.basicAuth` 供 swagger-ui 的 Authorize 按钮调试用。

一轮完整取值见 `.workbuddy/verify-openapi.sh`（三启对账：默认配置、开关关闭、路径改名）。
