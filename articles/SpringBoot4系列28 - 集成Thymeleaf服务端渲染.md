---
title: SpringBoot4系列28 - 集成Thymeleaf服务端渲染
slug: sb4-thymeleaf
date: 2026-09-20 22:32:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, Thymeleaf, 服务端渲染, BeanValidation ]
draft: false
---

公司内部的运营后台十有八九长一个样：商品录入、审批列表、参数配置页，页面结构简单，交互以表单提交加刷新列表为主。这种页面用前后端分离去做，我要维护两套代码、一条接口约定，还得排一次联调，投入产出比实在不划算。说实话，把页面拼装放回服务端，一个工程、一套部署就能交付，这类内部工具我一律上 Thymeleaf。Spring 官方对模板技术的推荐位很多年前就给了它：自然的 HTML 原型（直接双击就能打开）、与 Spring MVC 的表单绑定、开箱即用的 Bean Validation 错误回显，都是我需要的东西。

本篇我在 Spring Boot 4.1.1（Spring Framework 7.0.9、JDK 21.0.10）上从 starter 组成与自动配置包名取证开始，搭一个商品运营后台：列表分页、新增/编辑表单带六种校验注解、提交后重定向闪属性。文里的状态码与页面片段全部来自同一份端到端验证脚本打出来的原始记录，关键证据我都留了底，你可以照着复算；配套工程 springboot4-thymeleaf，端口 18280。

![](https://static.xiongneng.me/thymeleaf-components-20260922072117.png)

## 依赖与自动配置事实

工程只引三个 starter，我先把依赖贴出来：

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

`spring-boot-starter-thymeleaf` 在 Boot 4.1.1 里没有改名，但它的 POM 实测只有两项 compile 依赖：`spring-boot-starter`（日志、自动配置基础）与 `spring-boot-thymeleaf`（模板引擎适配）。`spring-webmvc` 和内嵌 Tomcat 都不在其中，做 MVC 页面应用必须像上面这样叠加 `spring-boot-starter-webmvc`，只引 thymeleaf starter 起来的进程没有任何 Web 端点。这个坑我踩过一回：进程起来了，端口死活不通，翻了半天配置才发现 starter 没配对。

我解包 `spring-boot-thymeleaf-4.1.1.jar` 确认了三件事。其一，自动配置类在 `org.springframework.boot.thymeleaf.autoconfigure` 包下，`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 里注册的就是 `ThymeleafAutoConfiguration`，`ThymeleafProperties` 同包，找配置类按这个包名来。其二，属性前缀 `spring.thymeleaf.*` 保留，`prefix` 默认 `classpath:/templates/`、`suffix` 默认 `.html`，`cache`、`mode`、`check-template` 等字段照旧；例外是 `spring.thymeleaf.enabled` 已废弃，元数据标注 error 级别，给出的理由是直接依赖 Thymeleaf 本身即可关闭自动配置。其三，模板引擎与 Spring 的桥接件是 `thymeleaf-spring6:3.1.5.RELEASE`，Boot 4.1 官方就用它与 Framework 7.0.9 搭配，没有单独的 spring7 桥接包，这个组合是我从依赖树里实测确认的。

BOM 管理的版本线如下（`spring-boot-dependencies:4.1.1` POM 实测），做依赖仲裁时我直接查这份表：

| 项 | 版本 |
|---|---|
| thymeleaf / thymeleaf-spring6 | 3.1.5.RELEASE |
| thymeleaf-extras-springsecurity6 | 3.1.5.RELEASE |
| thymeleaf-layout-dialect | 4.0.1 |
| hibernate-validator | 9.1.3.Final |
| jakarta.validation-api | 3.1.1 |

表单校验这条线全部走 Bean Validation 标准：`spring-boot-starter-validation` 传递引入 `hibernate-validator:9.1.3.Final` 与 `jakarta.validation-api:3.1.1`，注解写在 `jakarta.validation.constraints` 包下。测试侧有个拆分要注意，BOM 里有 `spring-boot-starter-thymeleaf-test`，但它只携带 `spring-boot-starter-test` 的纯 JUnit 设施；要用 MockMvc 做页面断言，测试 starter 得换 `spring-boot-starter-webmvc-test`。我第一次就引错了，测试上下文里视图解析直接报失败，九成是这个原因。

`mvn dependency:tree` 打出来的 thymeleaf 分支值得贴一段，starter 的组成一目了然，挺有意思的是分支里连一个 servlet 容器节点都没有：

```text
+- org.springframework.boot:spring-boot-starter-thymeleaf:jar:4.1.1:compile
|  +- org.springframework.boot:spring-boot-starter:jar:4.1.1:compile
|  \- org.springframework.boot:spring-boot-thymeleaf:jar:4.1.1:compile
|     \- org.thymeleaf:thymeleaf-spring6:jar:3.1.5.RELEASE:compile
|        \- org.thymeleaf:thymeleaf:jar:3.1.5.RELEASE:compile
```

看完这棵树，「必须叠加 webmvc starter」的结论就是铁证了。Boot 把自动配置从单体 jar 拆到各模块之后，每个 starter 的真实内容都要以依赖树为准，POM 描述与名字容易给人过时的印象，我现在的习惯是先跑 tree 再下结论。

模板文件的存放位置默认在 `src/main/resources/templates/`，静态资源在 `src/main/resources/static/`。本工程的完整布局是模板三页加一个片段库：

```text
src/main/resources/
├── templates/
│   ├── fragments/layout.html   # head / nav / footer 三个片段
│   └── products/
│       ├── list.html           # 列表页
│       └── form.html           # 新增与编辑共用
├── static/css/style.css
├── messages.properties         # typeMismatch 中文文案
└── application.yml
```

控制器返回的视图名 `products/list` 会按 `spring.thymeleaf.prefix + 视图名 + spring.thymeleaf.suffix` 解析成 `classpath:/templates/products/list.html`。记住这一条解析规则，后面排查模板找不到的问题全靠它。

## 模板组织：片段复用与布局

页面不多时我不引 thymeleaf-layout-dialect，用 `th:fragment` 加 `th:replace` 自己拼布局就够。工程把公共骨架放进 `templates/fragments/layout.html`：

```html
<head th:fragment="head(title)">
    <meta charset="UTF-8">
    <title th:text="${title}">商品管理</title>
    <link rel="stylesheet" th:href="@{/css/style.css}">
</head>
<nav th:fragment="nav">
    <div class="navbar">
        <span class="brand" th:text="${appName}">商品运营后台</span>
        <a th:href="@{/products}">商品列表</a>
        <a th:href="@{/products/new}">新增商品</a>
    </div>
</nav>
```

`th:fragment="head(title)"` 声明了一个带参数的片段，`title` 是参数名；`th:href="@{/css/style.css}"` 是链接表达式，部署在带 context-path 的路径下时会自动补前缀。导航条里的 `appName` 来自一个 `@ControllerAdvice` 的 `@ModelAttribute` 方法，所有视图不用重复传这个属性：

```java
@ControllerAdvice
public static class GlobalPageAdvice {

    @ModelAttribute("appName")
    public String appName() {
        return "商品运营后台";
    }
}
```

控制器 advice 在每个请求渲染前执行 `@ModelAttribute` 方法，返回值以 `appName` 为 key 放进模型，任何模板里都能直接取，我不必在每个控制器方法里手写一遍。

列表页复用这三个片段的方式：

```html
<head th:replace="~{fragments/layout :: head('商品列表')}"></head>
<body>
<nav th:replace="~{fragments/layout :: nav}"></nav>
<main class="container">
    <h1>商品管理</h1>
    <!-- 页面主体 -->
</main>
<footer th:replace="~{fragments/layout :: footer}"></footer>
</body>
```

`th:replace` 把当前标签整体替换成目标片段，`~{fragments/layout :: head('商品列表')}` 的意思是取 layout.html 里名为 head 的片段并传入标题参数。被替换的标签写什么内容都无所谓，我一律写空标签，让模板文件保持安静。表格主体是模板引擎的经典用法：

```html
<tr th:each="p : ${pageResult.items}">
    <td th:text="${p.id}">1</td>
    <td th:text="${p.name}">商品名</td>
    <td th:text="${#numbers.formatDecimal(p.price, 1, 2)}">0.00</td>
    <td><a th:href="@{'/products/' + ${p.id} + '/edit'}">编辑</a></td>
</tr>
```

`th:each` 遍历模型里的分页切片，`th:text` 输出前做 HTML 转义，商品名称里出现尖括号也不会破坏页面；要输出未转义的富文本才用 `th:utext`。`#numbers` 是内置工具对象，`formatDecimal(p.price, 1, 2)` 把价格固定成两位小数。翻页链接带查询参数：

```html
<a th:if="${pageResult.page > 1}"
   th:href="@{/products(page=${pageResult.page - 1}, keyword=${keyword})}">上一页</a>
```

`@{/products(page=..., keyword=...)}` 的括号语法生成带查询参数的 URL 并做 URL 编码，`th:if` 控制页码越界时不渲染无效链接。

翻页链接背后的分页切片在服务里完成，模板拿到的永远是收敛过的安全值：

```java
public Page page(int page, int size, String keyword) {
    int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    List<Product> all = search(keyword);
    int totalPages = Math.max(1, (all.size() + safeSize - 1) / safeSize);
    int safePage = Math.max(1, Math.min(page, totalPages));
    int from = (safePage - 1) * safeSize;
    int to = Math.min(from + safeSize, all.size());
    return new Page(all.subList(from, to), safePage, safeSize, all.size(), totalPages);
}
```

页码与页大小两个入参都被夹进有效区间，用户手输 `page=999` 或 `size=0` 时页面照常渲染最后一页。我的原则是查询参数的防御放在服务层，模板表达式不适合承担判断逻辑。

分类下拉框是另一个模板常用形态，选项列表来自服务的常量：

```html
<select id="category" th:field="*{category}" th:errorclass="input-error">
    <option value="">请选择分类</option>
    <option th:each="c : ${T(com.xncoding.thymeleaf.service.ProductService).CATEGORIES}"
            th:value="${c}" th:text="${c}">分类</option>
</select>
```

`th:field` 挂在 select 上时自动给匹配当前值的 option 补 `selected` 属性，编辑页打开时下拉框停在已选分类上。`T(...)` 是 SpringEL 的静态成员语法，选项列表固定时用它免去每个请求手工传模型；选项动态变化时就得改成控制器放进模型再传。

## 表单绑定与校验

服务端渲染的表单校验分三层：注解校验、类型转换、业务校验，三层失败都走同一条回显通道。表单对象我让它独立于实体定义，模板只面对它做绑定：

```java
public class ProductForm {

    private Long id;

    @NotBlank(message = "商品名称不能为空")
    @Size(min = 2, max = 20, message = "商品名称长度需在 {min} 到 {max} 个字符之间")
    private String name;

    @NotBlank(message = "商品分类不能为空")
    @Pattern(regexp = "DIGITAL|BOOKS|CLOTHING|FOOD", message = "商品分类必须是预置的四类之一")
    private String category;

    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0.01", message = "售价必须大于 0")
    private BigDecimal price;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    @Size(max = 50, message = "简介不能超过 {max} 个字符")
    private String summary;
}
```

六个注解覆盖了常用形态：`@NotBlank` 管非空，`@Size` 管长度区间，`@Pattern` 管正则枚举，`@NotNull` 与 `@DecimalMin` 管数值，`@Min` 管整数下限。注解消息里的 `{min}`、`{max}` 占位符由校验实现自动替换。表单对象与实体分离还有个实际好处：新增和编辑共用一个类型，字段增减只改一处。

控制器里 POST 方法把校验结果和表单对象一起接住：

```java
@PostMapping
public String create(@Valid @ModelAttribute("form") ProductForm form,
                     BindingResult binding,
                     RedirectAttributes redirectAttributes) {
    if (productService.existsByName(form.getName(), null)) {
        binding.rejectValue("name", "Duplicate", "同名商品已存在");
    }
    if (binding.hasErrors()) {
        return "products/form";
    }
    Product created = productService.create(form);
    redirectAttributes.addFlashAttribute("message", "已新增商品：" + created.getName());
    return "redirect:/products";
}
```

这段代码有三个细节要说清。`@Valid` 与紧随其后的 `BindingResult` 参数必须成对出现且顺序相邻，Spring 把校验失败收集进 `BindingResult` 而不抛异常；漏写这个参数，校验失败会直接抛 `BindException` 变成 400 白页。`rejectValue` 是注解之外的补充通道，重名检查要查仓储才能下结论，检查完把错误挂到 `name` 字段上，模板侧的回显代码与注解错误完全一致。`hasErrors()` 为真时返回表单页视图名，这就是「校验失败回显」的落点，状态码保持 200。

模板侧的绑定与错误展示：

```html
<form method="post" th:action="@{/products}" th:object="${form}">
    <div class="field">
        <label for="name">商品名称</label>
        <input type="text" id="name" th:field="*{name}" th:errorclass="input-error">
        <span class="error" th:if="${#fields.hasErrors('name')}" th:errors="*{name}">名称错误</span>
    </div>
</form>
```

`th:object="${form}"` 声明表单对象后，`th:field="*{name}"` 用选择表达式取字段，一次性完成三件事：生成 `name="name"` 与 `id="name"` 属性、把当前值渲染成 `value` 属性、在字段出错时把错误信息注册进 `#fields` 上下文。`th:errorclass` 在该字段有错时才追加上去的样式类，输入框自动标红。`th:errors="*{name}"` 输出该字段的全部错误消息，注解校验、类型转换、`rejectValue` 挂上去的错误都在其中。

类型转换失败的处理容易被漏掉：价格输入框传上来的是字符串，`abc` 转不成 `BigDecimal`，Spring 会把 `typeMismatch` 错误码挂到字段上，默认消息是英文，直接漏出去就是一屏英文报错。把消息解析接到 Spring 的 `MessageSource`，再补一个资源文件就能中文化：

```java
@Bean
public MessageSource messageSource() {
    ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
    source.setBasename("classpath:messages");
    source.setDefaultEncoding("UTF-8");
    return source;
}

@Bean
public LocalValidatorFactoryBean validator(MessageSource messageSource) {
    LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();
    factoryBean.setValidationMessageSource(messageSource);
    return factoryBean;
}
```

`LocalValidatorFactoryBean` 把 Bean Validation 的消息解析委托给 Spring 容器，`typeMismatch` 与注解消息走同一套解析。

```properties
typeMismatch.java.math.BigDecimal=售价格式不正确，请输入数字
typeMismatch.java.lang.Integer=库存格式不正确，请输入整数
```

错误码按「类型转换错误码 + 目标类型全限定名」匹配，`price` 字段收到 `abc` 时回显「售价格式不正确，请输入数字」。

编辑页的进入逻辑与新增页对称，区别在于要把实体字段拷进表单对象：

```java
@GetMapping("/{id}/edit")
public String editForm(@PathVariable Long id, Model model) {
    Product product = productService.getById(id);
    ProductForm form = new ProductForm();
    form.setId(product.getId());
    form.setName(product.getName());
    form.setCategory(product.getCategory());
    form.setPrice(product.getPrice());
    form.setStock(product.getStock());
    form.setSummary(product.getSummary());
    model.addAttribute("form", form);
    return "products/form";
}
```

字段拷贝看着笨拙，表单对象与实体分离的工程里这一步躲不掉，字段多时用 BeanUtils 之类工具类收敛。拷贝之后 `th:field` 在渲染时从表单对象取值，输入框的 `value` 属性自动带上实体现值，实测编辑页 `value="无线鼠标"` 就是这么来的。

工程配置极简，`application.yml` 只有端口与两个开发期属性：

```yaml
server:
  port: ${SERVER_PORT:18280}

spring:
  application:
    name: springboot4-thymeleaf
  thymeleaf:
    cache: false
    encoding: UTF-8
```

`cache: false` 只为改模板后刷新即见，生产环境保持默认。`encoding` 指定模板读取编码，中文页面固定 UTF-8。

## 提交-重定向-闪属性

表单提交成功后直接渲染列表页会有一个经典的重复提交问题：提交后的浏览器停留在 POST 响应上，刷新一次就重复入库一次。Post-Redirect-Get 模式是标准解法，Spring MVC 里一行返回值：

```java
redirectAttributes.addFlashAttribute("message", "已新增商品：" + created.getName());
return "redirect:/products";
```

返回值以 `redirect:` 开头时，Spring MVC 返回 302 状态码并把目标地址写进 `Location` 头，浏览器对列表页重新发起 GET。`addFlashAttribute` 把属性放进 FlashMap，FlashMap 在重定向前暂存于会话，目标请求被控制器处理前取出合并进模型，用完即焚，刷新列表页不会再看到成功提示。列表页的消费端只有一行：

```html
<div class="flash" th:if="${message}" th:text="${message}">操作成功</div>
```

`th:if` 判空，没有闪属性时这个提示块不会出现在 HTML 里。新增与编辑共用 `form.html`，提交地址按 `form.id` 区分：

```html
<form method="post"
      th:action="${form.id != null} ? @{/products/{id}(id=${form.id})} : @{/products}"
      th:object="${form}">
```

编辑路径的 `{id}` 占位符由 URI 模板语法填充，两个分支最终都回到列表页，成功提示文案由控制器各自设置。

![](https://static.xiongneng.me/thymeleaf-sequence-submit-20260922072117.png)

## 完整案例与端到端验证

![](https://static.xiongneng.me/thymeleaf-page-layout-20260922072117.png)

测试用 `@SpringBootTest` 加 `@AutoConfigureMockMvc`（Boot 4 中这个注解不再隐式提供，包在 `org.springframework.boot.webmvc.test.autoconfigure`），断言用 AssertJ 风格的 `MockMvcTester`。三个核心用例对应三条关键路径：

```java
@Test
@DisplayName("空表单提交：返回 200 回显表单，逐字段展示校验文案")
void emptySubmitRendersFieldErrors() {
    MvcTestResult result = mockMvc.post().uri("/products").exchange();
    String body = bodyText(result);

    result.assertThat().hasStatusOk();
    assertThat(body).contains("商品名称不能为空");
    assertThat(body).contains("售价不能为空");
}
```

断言落在渲染出来的 HTML 文本上，这正是服务端渲染测试与接口测试的分野：接口测 JSON 字段，页面测 HTML 片段。

```java
@Test
@DisplayName("合法提交：302 重定向到列表页，闪属性携带成功提示")
void validSubmitRedirectsWithFlash() {
    MvcTestResult result = mockMvc.post().uri("/products")
            .param("name", "测试蓝牙音箱")
            .param("category", "DIGITAL")
            .param("price", "199.00")
            .param("stock", "30")
            .exchange();

    result.assertThat()
            .hasStatus(302)
            .hasRedirectedUrl("/products");
    Object flashMessage = result.getMvcResult().getFlashMap().get("message");
    assertThat(flashMessage).isEqualTo("已新增商品：测试蓝牙音箱");
}
```

`getFlashMap()` 直接断言闪属性内容，不必跟踪会话状态。四个校验注解加类型转换的混合错误也有专门用例，一条请求把五类错误一次打齐：

```java
MvcTestResult result = mockMvc.post().uri("/products")
        .param("name", "X")
        .param("category", "UNKNOWN")
        .param("price", "abc")
        .param("stock", "-5")
        .param("summary", "x".repeat(60))
        .exchange();
```

断言覆盖五条文案：长度下限、枚举正则、类型转换中文提示、整数下限、字符串上限，全部出现在同一份 HTML 里。这类用例锁住了回显通道的完整性，任何一层出错通道被改动都能第一时间发现。

`mvn test` 跑完 10 个用例全绿。我又写了一份端到端验证脚本，把应用打成 jar 后以真实 HTTP 走一遍，脚本里对列表页的探测长这样：

```bash
code=$(curl -sS -o list.html -w "%{http_code}" "http://127.0.0.1:18280/products")
grep -oE '共 [0-9]+ 条 / [0-9]+ 页' list.html
```

curl 抓状态码与页面落盘，关键片段交给 grep 抽取，整个过程可重复执行，原始记录我都留了底。摘要如下：

| 步骤 | 请求 | 实测结果 |
|---|---|---|
| 列表页 | GET /products | 200，共 12 条 / 2 页，翻页链接 `page=2` |
| 第二页 | GET /products?page=2 | 200，剩余行渲染完整 |
| 新增表单 | GET /products/new | 200，action 为 `/products` |
| 空表单提交 | POST /products | **200**，回显四条字段错误文案 |
| 合法提交 | POST /products | **302**，Location 指向 /products |
| 重定向后 | GET /products?keyword=验证蓝牙音箱 | 200，命中新行 `<td>验证蓝牙音箱</td>` |
| 重名提交 | POST /products | 200，回显「同名商品已存在」 |
| 编辑回显 | GET /products/1/edit | 200，`value="无线鼠标"` |

反直觉的口径我单独拎一段说：服务端渲染场景下，校验失败返回 200 加表单重渲染，不是 REST 场景的 400 或 422。对浏览器来说校验失败就是一次正常的页面响应，错误信息在页面里而不是响应体结构里。我第一次给这类页面写断言就闹了笑话，照 REST 直觉用 400 写的用例全军覆没，盯着响应琢磨半天才承认问题出在自己的口径上。这是模板驱动开发与接口开发的根本差异，测试断言得跟着页面走。应用日志 ERROR 行数为 0，无堆栈泄漏。

原始记录里还有一处值得解释的细节：302 的 `Location` 头带上了 `;jsessionid=` 后缀。curl 未开启 Cookie 会话时，容器把会话标识回退到 URL 重写策略；浏览器场景下 Cookie 生效，这个后缀不会出现。脚本报数时我按原样记录，免得后续对照时起疑。

messages.properties 用中文文案时注意编码：文件按 UTF-8 保存，`messageSource` 里显式 `setDefaultEncoding("UTF-8")`，两处对齐后中文文案才能原样渲染。JDK 的属性文件早就原生支持 UTF-8，不必再做 \uXXXX 转义，编码没对齐的症状是页面文案变成乱码，先查这两处。

![](https://static.xiongneng.me/thymeleaf-verify-panel-20260920230000.png)

## 避坑指南

**坑一，只引 starter-thymeleaf 起不来 Web 端点。** 这个 starter 实测不含 `spring-webmvc` 与 Tomcat，单引它的进程只有一个空应用上下文；MVC 页面应用必须与 `spring-boot-starter-webmvc` 成对引入。

**坑二，`spring.thymeleaf.enabled=true` 是无效配置。** 该属性已废弃，元数据标注 error 级别，给出的理由是关闭自动配置应改为直接依赖 Thymeleaf 本身；配置文件里带着这行会直接启动失败，删掉即可。

**坑三，`@Valid` 后面漏接 `BindingResult`。** 两个参数必须相邻成对，漏写时校验失败直接抛 `BindException`，浏览器看到的是 400 错误页而不是带回显的表单页。

**坑四，GET 表单页忘记往模型里放表单对象。** `th:object="${form}"` 解析不到模型属性时抛模板异常，页面 500；新增页进入方法的第一行就是 `model.addAttribute("form", ProductForm.empty())`。

**坑五，类型转换错误显示成英文 typeMismatch。** `price` 传 `abc` 这类错误默认走英文消息，需要把 `LocalValidatorFactoryBean` 的消息源接到 Spring `MessageSource` 并补 `typeMismatch.目标类型全限定名` 文案，只写注解的 `message` 覆盖不到这一层。

**坑六，校验失败状态码按 REST 直觉写成 400。** 服务端渲染的校验失败就是 200 加表单重渲染，错误信息在页面里而不是响应体结构里；照 REST 口径写测试断言会全部落空，我自己就栽过一回。

**坑七，成功提示用普通 `Model` 属性传给重定向目标。** `model.addAttribute` 的属性拼进 302 的 URL 变成查询参数，且刷新后常驻；闪属性要用 `RedirectAttributes.addFlashAttribute`，它由会话暂存、读取一次即清除。

**坑八，生产环境忘开模板缓存。** `spring.thymeleaf.cache` 默认 true，开发期常被改成 false 方便热改，上线前记得删掉这行；缓存关闭时每次渲染都读磁盘模板，高并发下拖慢响应还多占句柄。

**坑九，把 `th:utext` 当 `th:text` 用。** `utext` 不做转义，用户输入的 `<script>` 会原样进入页面形成存储型 XSS；富文本渲染前在服务端消毒，普通字段一律 `th:text`。

**坑十，用 JSP 的习惯找 `webapp` 目录。** Boot 的 Thymeleaf 模板固定在 `classpath:/templates/` 下（`spring.thymeleaf.prefix` 默认值），打成 jar 后没有文件系统的 `webapp` 目录一说；要换位置改 prefix，别去迁就 JSP 布局。

**坑十一，编辑提交后校验失败丢失 ID。** 表单对象由请求参数重建，POST 目标是 `/products/{id}` 时请求参数里没有 id 字段，校验失败重渲染前必须先 `form.setId(id)`，否则表单的提交地址退回新增分支。

**坑十二，测试环境模板找不到先查测试 starter。** `spring-boot-starter-thymeleaf-test` 只带 JUnit 设施，页面断言用的 MockMvc 设施在 `spring-boot-starter-webmvc-test` 里；引错 starter 的症状是测试上下文里 Thymeleaf 自动配置缺失，报视图解析失败。

## 小结

选型建议：页面以表单提交加列表刷新为主的内部后台，我会直接选 Thymeleaf 服务端渲染，一个工程交付，校验回显与闪属性这些脏活框架全包；交互重、前端状态复杂的产品级界面，还是老老实实前后端分离。动手前记两件事：webmvc 与 thymeleaf 两个 starter 成对引入，自动配置类按 `org.springframework.boot.thymeleaf.autoconfigure` 这个包名去找。

没解决的事：thymeleaf-layout-dialect 的声明式布局我没实测，页面规模上去之后片段拼布局会不会力不从心，得等真实工程给答案；模板缓存打开之后的生产并发表现，我也只在很小的量级上碰过。这两个空缺都留在配套工程里，环境齐了随时能补。

## 参考链接

- [Spring Boot 4.1 Reference - Servlet Web Applications](https://docs.spring.io/spring-boot/4.1/reference/web/servlet.html)：Spring MVC 与模板引擎、`spring.thymeleaf.*` 属性的官方章节
- [Thymeleaf 3.1 - Using Thymeleaf](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html)：标准表达式语法、`th:fragment`/`th:replace` 布局与内置工具对象
- [Thymeleaf 3.1 - Thymeleaf + Spring](https://www.thymeleaf.org/doc/tutorials/3.1/thymeleafspring.html)：`th:object`/`th:field` 表单绑定与 `#fields` 错误对象
- [Spring Boot 4.1 Reference - Validation](https://docs.spring.io/spring-boot/4.1/reference/data/validation.html)：Bean Validation 自动配置与 `LocalValidatorFactoryBean`
- [Spring Framework 7 - Validation, Data Binding, and Type Conversion](https://docs.spring.io/spring-framework/reference/core/validation.html)：`BindingResult`、`typeMismatch` 错误码与消息解析机制
