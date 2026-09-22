# springboot4-thymeleaf

Spring Boot 4 教程第 28 篇配套工程：集成 Thymeleaf 服务端渲染 + 表单校验。

## 场景

商品运营后台（服务端渲染页面）：

- 列表页 `/products`：关键词过滤 + 分页表格（`th:each` 渲染、翻页链接）
- 新增/编辑表单 `/products/new`、`/products/{id}/edit`：`th:object`/`th:field` 绑定、`th:errors` 回显校验错误
- 提交后重定向：`redirect:/products` + Flash Attribute（成功提示只显示一次）
- 业务校验：重名拒绝（`BindingResult.rejectValue`）
- 类型转换错误中文化：`messages.properties` 的 `typeMismatch.*` 文案

## 技术栈

| 项 | 版本 |
|---|---|
| Spring Boot | 4.1.1（parent） |
| Spring Framework | 7.0.9 |
| Thymeleaf | 3.1.5.RELEASE（thymeleaf-spring6） |
| Hibernate Validator | 9.1.3.Final（jakarta.validation-api 3.1.1） |
| JDK | 21 |
| 端口 | 18280（`SERVER_PORT` 环境变量可覆盖） |

## 关键依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

注意：`spring-boot-starter-thymeleaf` 只带模板引擎（不含 `spring-webmvc` 与 Tomcat），必须与 `spring-boot-starter-webmvc` 叠加使用。Boot 4 中自动配置类已迁移到 `org.springframework.boot.thymeleaf.autoconfigure` 包。

## 运行

```bash
mvn spring-boot:run
# 或
mvn package && java -jar target/springboot4-thymeleaf-1.0.0.jar
```

访问 <http://localhost:18280/products>。

## 测试

```bash
mvn test
```

`ProductPagesTest` 覆盖 10 个用例：列表渲染与分页、关键词过滤、空表单提交回显字段错误、四类校验错误同时回显、合法提交 302 重定向与 FlashMap、重名拒绝、编辑页回显与编辑提交。

## 目录结构

```
src/main/java/com/xncoding/thymeleaf/
├── ThymeleafApplication.java   # 启动类
├── controller/ProductController.java  # GET 列表 + GET/POST 表单
├── service/ProductService.java # 内存仓储 + 分页
├── domain/Product.java         # 商品实体
├── form/ProductForm.java       # 表单对象（6 种校验注解）
└── web/WebConfig.java          # MessageSource + 校验器 + 全局页面属性
src/main/resources/
├── templates/fragments/layout.html  # head/nav/footer 片段
├── templates/products/list.html     # 列表页
├── templates/products/form.html     # 新增/编辑共用表单页
├── static/css/style.css
├── messages.properties              # typeMismatch 中文文案
└── application.yml
```

---

作者：Xiong Neng
