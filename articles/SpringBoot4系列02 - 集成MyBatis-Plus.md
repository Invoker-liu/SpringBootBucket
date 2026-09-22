---
title: SpringBoot4系列02 - 集成MyBatis-Plus
slug: sb4-mybatis
date: 2026-09-18 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, MyBatis-Plus, MySQL ]
draft: false
---

第 1 篇收尾的时候我留了一句话：`OrderRepository` 接口先空着，MyBatis、JPA、MongoDB 三篇各给一个实现类，外层代码一行不动。这一篇来兑现第一个承诺：把内存仓储换成 MyBatis-Plus，订单落进 MySQL。接口契约我一个字没改，坑倒是比预想的多，那个悬了两篇的 Jackson 3 坑也在这一篇掉进去了。

代码全部可跑，工程在 `springboot4-mybatis` 目录下。需要一台能连的 MySQL，我用的库跑在树莓派的容器里，建表脚本一条命令跑完。

## MyBatis-Plus 到底站在 MyBatis 的哪一层

我心里先给 MyBatis 定位：它是个 SQL 映射框架，SQL 由我来写，它负责塞参数、映射结果，不管 SQL 本身怎么写。MyBatis-Plus 没有替换它，是在它上面加了一层，加的东西归三类。

![](https://static.xiongneng.me/mybatis-plus-position-20260921211304.png)

通用 CRUD：`BaseMapper<T>` 直接给增删改查，单表零 SQL。条件构造器：`LambdaQueryWrapper` 用方法引用指列名，属性改名编译期就报错。插件：分页、乐观锁、逻辑删除、多租户、数据权限以「拦截器」形式挂执行链，注册几个用几个。

这篇的工程里，第 1 篇那套接口一行路径没改，内存仓储换成 `OrderMapper`，我注册了分页和乐观锁两个插件。

| 方法 | 路径 | 变化 |
|---|---|---|
| POST | `/api/orders` | 照旧服务端生成，加唯一键冲突重试 |
| GET | `/api/orders/{id}` | 无 |
| GET | `/api/orders` | 分页改走 SQL 的 `LIMIT` |
| GET | `/api/orders/deleted` | 新增，回收站，手写 SQL |
| GET | `/api/orders/stats` | 新增，聚合统计，手写 SQL |
| PUT | `/api/orders/{id}` | 多了可选版本号，支持乐观锁 |
| PATCH | `/api/orders/{id}/status` | 无 |
| DELETE | `/api/orders/{id}` | 从真删除变成逻辑删除 |

契约第 1 篇就写死了：`OrderRepository` 抽成接口，内存实现叫 `InMemoryOrderRepository`，等着今天换。

## 3.5.17 在 Boot 4 上的四个要点

![](https://static.xiongneng.me/mybatisplus-boot4-essentials-20260921211304.png)

一，starter 坐标。Boot 4 下用 `mybatis-plus-spring-boot4-starter`，官方为 Boot 4 单独出的坐标，`mybatis-plus-spring-boot-starter` 不在 Boot 4 适配范围内。这个 starter 自带 `spring-boot-starter-jdbc`，pom 里再引一遍 JDBC starter 就是重复依赖，说实话不引白不引的便宜在这没有，多一个坐标多一分版本漂移的风险。

上一篇我留过「3.5.13 之后它专门出了适配 Boot 4 的 starter」，这次拿 Maven 挨个拉坐标验证了一遍：

```
mybatis-plus-spring-boot4-starter:3.5.12   → 不存在
mybatis-plus-spring-boot4-starter:3.5.13   → 存在
mybatis-plus-spring-boot4-starter:3.5.14   → 存在
```

起点是 3.5.13，这篇用 3.5.17。

二，分页插件不在 starter 里。

> 于 `v3.5.9` 起，`PaginationInnerInterceptor` 已分离出来。如需使用，则需单独引入 `mybatis-plus-jsqlparser` 依赖。

只引 starter，`PaginationInnerInterceptor` 这个类找不到，编译过不去，我第一把就卡死在这行 import 上。工程里导入官方 BOM 统一版本，避免 `mybatis-plus-jsqlparser` 和核心包错位。

三，`MapperScan` 的包路径。`com.baomidou.mybatisplus.annotation.MapperScan` 在 3.5.17 里不存在，写成这个包路径直接编译失败；官方推荐 `org.mybatis.spring.annotation.MapperScan`。我差点照着 IDEA 的自动补全把它写上去，好在先查了文档，没闹笑话。这篇接口上直接标 `@Mapper`，只有一个 Mapper，写配置类扫包不划算。

四，拦截器注册顺序有语义，官方文档也有提醒。

> 在使用多个插件时，请将分页插件放到插件执行链的最后面，以避免 COUNT SQL 执行不准确的问题。

拦截器按顺序改写 SQL，分页往 SQL 尾部追加 `LIMIT`，排在前头的话后面还有拦截器再动，`COUNT` 就可能算错。分页放最后。

## 依赖和配置

pom 里跟数据库有关的部分，我摘出来。

```xml
<properties>
    <java.version>21</java.version>
    <mybatis-plus.version>3.5.17</mybatis-plus.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- 用官方 BOM 统一版本，避免核心包与 jsqlparser 错位 -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-bom</artifactId>
            <version>${mybatis-plus.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 注意 boot4 后缀，不能省 -->
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
    </dependency>
    <!-- 分页插件自 3.5.9 起分离，不引这个 PaginationInnerInterceptor 找不到 -->
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-jsqlparser</artifactId>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

实际拉出来的依赖树，跟这个主题有关的是这几个。

```
com.baomidou:mybatis-plus-spring-boot4-starter:jar:3.5.17
+- com.baomidou:mybatis-plus:jar:3.5.17
|  +- com.baomidou:mybatis-plus-core:jar:3.5.17
|  \- org.mybatis:mybatis:jar:3.5.19
+- org.mybatis:mybatis-spring:jar:4.0.0
\- org.springframework.boot:spring-boot-starter-jdbc:jar:4.1.1
com.baomidou:mybatis-plus-jsqlparser:jar:3.5.17
com.mysql:mysql-connector-j:jar:9.7.0
```

`spring-boot-starter-jdbc` 是 starter 自带的，HikariCP 也在里面，不用单独引；`mybatis-spring` 到了 4.0.0，版本号跟 Boot 4 走。

`application.yml` 里两处是重点，其余是常规数据源配置。

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:192.168.1.97}:${DB_PORT:3306}/${DB_NAME:springboot4_mybatis}?...
    hikari:
      maximum-pool-size: 10
      connection-timeout: 5000

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false
    db-config:
      logic-delete-value: 1
      logic-not-delete-value: 0
```

连接信息走环境变量，默认值指向我的局域网地址，你跑之前记得改成自己的；库名带 `springboot4_` 前缀，不和别的工程撞。`map-underscore-to-camel-case` 必须开，`order_no` 到 `orderNo` 的转换靠它。逻辑删除两个值有默认值，我写出来是为了让删除标记在配置里一眼可见。

## 核心代码

调用链比第 1 篇长了一截，中间多了 MyBatis 和插件这层。

![](https://static.xiongneng.me/layered-architecture-20260921211304.png)

Controller、Service、DTO 都是第 1 篇那套，一个字没改。变化在 Service 不再依赖 `OrderRepository`，直接拿 `OrderMapper`，往下是 MyBatis 执行链，插件挂在链上，落到 MySQL。

### 实体上的注解决定框架能做什么

先说我的结论：MyBatis-Plus 的能力从实体注解上读出来，不标，框架就不管。

```java
@TableName(value = "t_order", autoResultMap = true)
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<OrderItem> items;

    @TableLogic
    private Integer deleted;

    @Version
    @TableField(fill = FieldFill.INSERT)
    private Integer version;
}
```

每个注解背后都有一个「不写会怎样」，我逐个对过。

`@TableId(type = IdType.AUTO)` 不写，主键不走自增：默认策略 `ASSIGN_ID` 用雪花算法生成十九位长整型，`AUTO_INCREMENT` 列白设，必须显式声明。

`FieldStrategy.ALWAYS` 不写，更新接口传 `remark: null` 清空备注不生效：默认策略 `NOT_NULL` 把 null 当「这次不更新这一列」，不是「置空」。

`@TableLogic` 不写，`deleteById` 就是真删除。`@Version` 不写，`updateById` 就是无保护覆盖写。`Jackson3TypeHandler` 和 `autoResultMap = true` 放到 JSON 列那节细说。

实体我写成可变 POJO，没用 record：自动填充回写字段、乐观锁读版本号自增都依赖无参构造加 setter，record 得写一堆额外配置配合反射，不值。对外暴露的仍是不可变的 `OrderResponse`。

### 自动填充，以及一个不报错的坑

审计字段由 `MetaObjectHandler` 回写，业务代码不用碰。

```java
@Component
public class AuditFieldHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = now();
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "version", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, now());
    }

    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
```

我用 `strictInsertFill` 而不是 `setFieldValByName`：前者只在字段为 null 时填，显式赋的值不会被覆盖。

这里踩过一个不报错的坑，我到现在还记得当时对着响应发懵的样子。`version` 那行是插入时置 0，跟建表的 `DEFAULT 0` 对齐，写完创建订单，响应里没有 `version`。没有报错，静默跳过，最后在官方文档注意事项里找到了原因。

> 字段必须声明 `@TableField` 注解，并设置 `fill` 属性来选择填充策略。

`strictInsertFill` 只填充标了 `@TableField(fill = ...)` 的字段：`createdAt`、`updatedAt` 标了被填上，`version` 只标了 `@Version`，这行等于没写。不抛异常、不打日志，要不是写了测试，这个问题我可能到现在都没发现。补上 `@TableField(fill = FieldFill.INSERT)` 就好。

小细节是 `truncatedTo(ChronoUnit.MILLIS)`。`LocalDateTime.now()` 带纳秒，建表用的 `DATETIME(3)` 只存毫秒。我第一版创建接口返回：

```json
"createdAt":"2026-09-17T23:28:56.3591185"
```

紧接着查同一个订单：

```json
"createdAt":"2026-09-17T23:28:56.359"
```

两个值做等值比较永远不相等，填充时截断到毫秒。

![](https://static.xiongneng.me/auto-fill-and-version-20260921211304.png)

### 条件构造器里那对括号

分页查询是这次改动最大的地方，从「内存过滤加切片」变成「拼一条 SQL」。

```java
public PageResponse<OrderResponse> page(OrderPageQuery query) {
    // MyBatis-Plus 的页码从 1 开始，对外契约是 0 基，差异只在这一行消化
    Page<Order> page = new Page<>(query.page() + 1L, query.size());

    LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
            .and(StringUtils.hasText(query.keyword()), w -> w
                    .like(Order::getOrderNo, query.keyword())
                    .or()
                    .like(Order::getCustomerName, query.keyword()))
            .eq(query.status() != null, Order::getStatus, query.status())
            .orderBy(true, !query.descending(),
                    SORT_COLUMNS.getOrDefault(query.sortBy(), Order::getCreatedAt));

    Page<Order> result = orderMapper.selectPage(page, wrapper);
    return PageResponse.of(result.getRecords(), query.page(), query.size(),
            result.getTotal(), OrderResponse::from);
}
```

先看每个方法的第一个布尔参数，它是「是否拼接这个条件」：`keyword` 为空时那个 `like` 整段丢掉，不用拼一堆 if-else。

第二个是 `.and(...)` 那对括号，必须有。我的直觉写法是链式接两个 `like` 用 `or()` 连，SQL 是 `WHERE deleted=0 AND order_no LIKE ? OR customer_name LIKE ?`，`OR` 优先级把 `deleted=0` 拖进去。包一层出来 `WHERE deleted = 0 AND ((order_no LIKE ? OR customer_name LIKE ?))`，语义才对。

第三个是页码。`Page` 从 1 开始，第 1 篇的对外契约是 `page=0`，我把差异只在 `new Page<>(query.page() + 1L, ...)` 这一行消化。`Page` 的字段是 `current`、`size`、`total`、`records`，跟 Spring Data 的 `Pageable` 完全不同。`Page` 不能直接返回给前端，用的是自己的 `PageResponse`。

第四个是排序白名单。`ORDER BY` 列名没法参数化，我上了方法引用加白名单双重限制。

```java
private static final Map<String, SFunction<Order, ?>> SORT_COLUMNS = Map.of(
        "id", Order::getId, "orderNo", Order::getOrderNo,
        "customerName", Order::getCustomerName, "totalAmount", Order::getTotalAmount,
        "status", Order::getStatus, "createdAt", Order::getCreatedAt, "updatedAt", Order::getUpdatedAt);
```

客户端字符串先过 `OrderPageQuery` 的白名单，查不到的键 `getOrDefault` 回退默认字段，外部字符串进不了 SQL。官方文档在分页插件那一页也有提醒。

> 排序 `OrderItem` 对象 `column` 属性建议不要让前端传入，如果你允许前端传入，相关内容一定要做 白名单验证 避免 SQL 注入风险。

![](https://static.xiongneng.me/pagination-and-logic-delete-20260921211449.png)

### 分页插件到底做了什么

注册拦截器：

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

    PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
    pagination.setMaxLimit(100L);
    interceptor.addInnerInterceptor(pagination);   // 分页必须最后加
    return interceptor;
}
```

`DbType.MYSQL` 是我显式写的：单一数据库可以不配让它探测，多数据源下它会认错。`maxLimit` 设 100 是双保险，接口 `size` 已有 `@Max(100)`，这层防绕过校验直接调 Mapper。

分页插件做了什么，看 SQL 日志最直接。我带关键字跑了一次分页查询，执行了两条语句。

```
OrderMapper.selectList_mpCount: ==>  Preparing: SELECT COUNT(*) AS total FROM t_order
                                          WHERE deleted = 0 AND ((order_no LIKE ? OR customer_name LIKE ?))
OrderMapper.selectList_mpCount: ==>  Parameters: %ORD%(String), %ORD%(String)

OrderMapper.selectList: ==>  Preparing: SELECT id,order_no,customer_name,customer_phone,total_amount,
                                          status,remark,created_at,updated_at,items,deleted,version
                                      FROM t_order
                                      WHERE deleted=0 AND ((order_no LIKE ? OR customer_name LIKE ?))
                                      ORDER BY id ASC LIMIT ?
OrderMapper.selectList: ==>  Parameters: %ORD%(String), %ORD%(String), 2(Long)
```

挺有意思，一条方法调用拆成两条 SQL。三件事：`selectList_mpCount` 是框架按原查询生成的 `COUNT`，不用自己写，`ORDER BY` 被优化掉了；`deleted = 0` 是逻辑删除自动加的；第 0 页只有 `LIMIT ?` 没有偏移量，翻到后面才变 `LIMIT ?,?`，按方言拼的。

### 逻辑删除，和它管不到的地方

`deleteById` 执行的是一条 UPDATE。

```
OrderMapper.deleteById: ==>  Preparing: UPDATE t_order SET updated_at=?, deleted=1 WHERE id=? AND deleted=0
OrderMapper.deleteById: ==>  Parameters: 2026-09-17T23:43:23.226(LocalDateTime), 3(Long)
OrderMapper.deleteById: <==    Updates: 1
```

删除被转换成更新，标记记录已删除；`updated_at` 被填上了，逻辑删除不绕过自动填充，我实测 `deleteById` 同样触发 `updateFill`，官方文档「删除接口自动填充功能失效怎么办」的第一条建议就是 `deleteById`；`WHERE` 里的 `deleted=0` 是框架加的，第二次删同一行 `Updates: 0`，服务层返回 404。

还有一条边界，我原样抄在这里。

> `@TableLogic` 的过滤只加在 MyBatis-Plus 生成的 SQL 上，手写 XML 不会被追加。

坏的方向：手写查询都要自己写 `AND deleted = 0`，否则已删除数据会重新冒出来，聚合统计的 `WHERE deleted = 0` 是我手敲的，漏了就含回收站订单。好的方向：回收站这就从这儿来的，手写 `WHERE deleted = 1` 查逻辑删除的行。

```xml
<select id="selectDeleted" resultMap="orderResultMap">
    SELECT <include refid="allColumns"/>
    FROM t_order
    WHERE deleted = 1
    ORDER BY updated_at DESC
    LIMIT #{limit}
</select>
```

用 `ORDER BY updated_at` 是因为逻辑删除会更新它，倒序最近删掉的最靠前。

### JSON 列，以及上一篇说的那个 Jackson 3 的坑

工程给订单加了明细字段：一个订单若干条商品，整份列表序列化成 JSON 存进 `VARCHAR` 列，正好踩中上一篇预告的坑。

我的结论：Boot 4 下必须用 `Jackson3TypeHandler`，不能用 `JacksonTypeHandler`。`mybatis-plus-extension` 里有两个几乎一样的类，我拿 `javap` 看字段签名。

```
public class JacksonTypeHandler extends AbstractJsonTypeHandler<Object> {
  private static com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER;
}

public class Jackson3TypeHandler extends AbstractJsonTypeHandler<Object> {
  private static tools.jackson.databind.ObjectMapper OBJECT_MAPPER;
}
```

一个 `com.fasterxml.jackson`，一个 `tools.jackson`，对应 Jackson 3 换包名前后。Boot 4 只带 Jackson 3，用 `JacksonTypeHandler` 编译能过（类还在 jar 里），运行时直接找不到 `com.fasterxml.jackson.databind.ObjectMapper`。

`Jackson3TypeHandler` 是 3.5.17 才加的，我对比了两个版本的 jar：

```
mybatis-plus-extension-3.5.13.jar:
  com/baomidou/mybatisplus/extension/handlers/JacksonTypeHandler.class

mybatis-plus-extension-3.5.17.jar:
  com/baomidou/mybatisplus/extension/handlers/JacksonTypeHandler.class
  com/baomidou/mybatisplus/extension/handlers/Jackson3TypeHandler.class
```

上一篇留的话正是这里：「3.5.13 的 starter 能用了，但里面还有 Jackson 3 的坑」，用 3.5.13 没有这个类。

光标 typeHandler 不够，还有更隐蔽的一层，我在这里栽得最狠。

```java
@TableName(value = "t_order", autoResultMap = true)
public class Order {

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<OrderItem> items;
}
```

`autoResultMap = true` 必须打开。MyBatis 只在 resultMap 映射时应用自定义 typeHandler，MyBatis-Plus 默认走自动映射，不看 `@TableField`，找不到能转 `List` 的内置 handler 就报错。这个开关让框架生成带 typeHandler 的 resultMap，漏掉的症状是写入正常、读取永远 null、不报错。

第三处在手写 XML。回收站查询我一开始用 `resultType`，加 `items` 列就不行，`resultType` 走的也是自动映射，带自定义 typeHandler 的列必须用 `resultMap`。

```xml
<resultMap id="orderResultMap" type="com.xncoding.mybatis.domain.Order">
    <id column="id" property="id"/>
    <result column="order_no" property="orderNo"/>
    ...
    <result column="items" property="items"
            typeHandler="com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler"/>
</resultMap>
```

聚合统计那条没有 JSON 列，`resultType` 够用。规律我一句话说完：**只要涉及自定义 typeHandler，就必须落到某个 resultMap 上，要么框架生成，要么自己写。**

![](https://static.xiongneng.me/json-column-typehandler-20260921211304.png)

小细节：`Jackson3TypeHandler` 内部的 `ObjectMapper` 是它自己 `new` 的，`javap` 里能看到那一行。

```
0: new           #13                 // class tools/jackson/databind/ObjectMapper
4: invokespecial #15                 // Method tools/jackson/databind/ObjectMapper."<init>":()V
```

它跟 Spring 容器里的不是同一个实例：Boot 默认关掉「反序列化遇到未知字段报错」，裸实例是开着的，多塞一个字段读取就失败。所以 `OrderItem` 定字段时我一个多余的都没加，包括算出来就能得到的 `subtotal`，要算小计在别处算。

这份 JSON 在接口上的样子，浏览器打开演示数据第一条。

![](https://static.xiongneng.me/mybatis-browser-json-view-20260918125932.png)

`items` 是一段规整数组，`price` 的 `299.50` 保住了尾零。Chrome 默认把响应体压成一行，看分层结构点左上角「美观输出」。

## 完整案例，跑起来看

先建库。

```bash
mysql -h 127.0.0.1 -P 3306 -uroot -p < src/main/resources/sql/schema.sql
```

脚本建库建表，写 6 条覆盖全部 5 个状态的演示数据，可重复执行，每次删表重建。然后起服务。

```bash
mvn clean package
java -jar target/springboot4-mybatis-1.0.0.jar
```

下面的响应是实际跑出来的，一个字没改。截图和响应头里的 18081 是我录输出时临时指定的，免得跟占着 8080 的其他服务撞车；项目默认端口 8080，不用加参数。

**创建订单，这次带上了明细。**

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":299.50,"remark":"演示下单",
       "items":[{"name":"机械键盘","quantity":1,"price":299.50},{"name":"腕托","quantity":2,"price":25.00}]}'
```

```http
HTTP/1.1 201
Location: http://127.0.0.1:18081/api/orders/7
Content-Type: application/json

{"id":7,"orderNo":"ORD2026091860336229","customerName":"熊大","customerPhone":"138****8000",
 "totalAmount":299.50,"status":"CREATED","statusLabel":"已创建","remark":"演示下单",
 "items":[{"name":"机械键盘","quantity":1,"price":299.50},{"name":"腕托","quantity":2,"price":25.00}],
 "version":0,"createdAt":"2026-09-18T07:45:49.017","updatedAt":"2026-09-18T07:45:49.017"}
```

`version` 这次在了，插入语句里 `items` 已是一段 JSON 字符串。

```
==>  Preparing: INSERT INTO t_order ( order_no, customer_name, customer_phone, total_amount, status,
              remark, created_at, updated_at, items, version ) VALUES ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )
==>  Parameters: ORD2026091860336229(String), 熊大(String), 13800138000(String), 299.50(BigDecimal),
              CREATED(String), 演示下单(String),
              2026-09-18T07:45:49.017(LocalDateTime), 2026-09-18T07:45:49.017(LocalDateTime),
              [{"name":"机械键盘","quantity":1,"price":299.50},{"name":"腕托","quantity":2,"price":25.00}](String),
              0(Integer)
```

`(String)` 标注参数类型，列表进 JDBC 前已被处理。列清单里没有 `deleted`：实体上是 null，插入时跳过，落库靠列的 `DEFAULT 0`，即文档说的「插入时逻辑删除字段的值不受限制」。

![](https://static.xiongneng.me/mybatis-order-created-201-20260918130002.png)

**乐观锁。**

```bash
# 第一次，带上当前版本号 0
curl -i -X PUT http://localhost:8080/api/orders/7 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":199.00,"remark":"改价","version":0}'

# 第二次，还拿版本号 0，这个已经过期了
curl -i -X PUT http://localhost:8080/api/orders/7 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":666.00,"remark":"并发写入","version":0}'
```

第一次返回 200，版本号变 1。

```
==>  Preparing: UPDATE t_order SET customer_name=?, customer_phone=?, total_amount=?, remark=?,
                  updated_at=?, version=? WHERE id=? AND version=? AND deleted=0
==>  Parameters: 熊大(String), 13800138000(String), 199(BigDecimal), 改价(String),
                  2026-09-18T07:45:49.509(LocalDateTime), 1(Integer), 7(Long), 0(Integer)
<==    Updates: 1
```

`SET` 里的 `version` 绑新值 1，`WHERE` 里绑旧值 0。没有写成 `version = version + 1` 交给数据库算，新版本号是 MyBatis-Plus 在 Java 侧算好绑上去的，官方文档写明整数类型 `newVersion` 是 `oldVersion + 1` 并回写实体。

第二次返回 409。

```json
{
  "detail": "订单已被其他人修改，请刷新后重试",
  "instance": "/api/orders/7",
  "status": 409,
  "title": "业务规则不满足",
  "type": "urn:problem-type:business-rule-violation"
}
```

同样的 SQL，`Updates` 变 0，`WHERE version = 0` 匹配不到行了，这行数就是并发写被挡下的铁证。

版本号是可选的：`OrderUpdateRequest` 的 `version` 不传，乐观锁不生效直接覆盖，要不要并发保护由客户端决定。要强制就把注解从 `@PositiveOrZero` 换成 `@NotNull`。我留成可选是因为第 1 篇契约里没有版本号，加可选字段兼容，加必填是破坏性变更。

![](https://static.xiongneng.me/mybatis-optimistic-lock-409-20260918130002.png)

**字段校验失败，返回 400，逐字段原因。**

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":10.00,
       "items":[{"name":"","quantity":0,"price":0}]}'
```

```json
{
  "detail": "请求体字段校验未通过，逐字段原因见 errors",
  "instance": "/api/orders",
  "status": 400,
  "title": "请求体校验失败",
  "errors": [
    {"field": "items[0].price", "message": "商品单价必须大于 0"},
    {"field": "items[0].quantity", "message": "商品数量必须大于 0"},
    {"field": "items[0].name", "message": "商品名称不能为空"}
  ]
}
```

`errors` 里的 `items[0].price` 能让前端直接定位第几条明细的哪个字段。生效前提是 `OrderCreateRequest` 列表字段标了 `@Valid`。

```java
@Valid
@Size(max = 20, message = "订单明细最多 20 条")
List<OrderItem> items
```

少了 `@Valid`，`@Size` 还能挡「明细超过 20 条」，但 `OrderItem` 自己的校验注解全不执行，非法数据直接进库，很隐蔽，因为列表本身合法。

**逻辑删除与回收站。**

```bash
curl -i -X DELETE http://localhost:8080/api/orders/3   # 204
curl -i     http://localhost:8080/api/orders/3         # 404，查不到了
curl -s     http://localhost:8080/api/orders/deleted   # 200，回收站里查得到
```

```json
[{"id":3,"orderNo":"ORD20260901000003","customerName":"张三","customerPhone":"139****9000",
  "totalAmount":68.80,"status":"SHIPPED","statusLabel":"已发货","remark":"演示数据",
  "items":[{"name":"鼠标垫","quantity":1,"price":68.80}],"version":0,
  "createdAt":"2026-09-17T23:40:46.392","updatedAt":"2026-09-17T23:40:58.948"}]
```

`items` 能出来，说明手写 XML 的 `resultMap` 有效；`updatedAt` 是删除那一刻的时间，说明 `updated_at` 被自动填充。

21 个场景是我一个一个 curl 过去的，状态码按序 200、200、200、201、200、200、409、200、200、422、204、404、200、200、400、404、400、400、405、200、422，全部符合预期，应用日志 ERROR 行数为 0。

## 这一篇的测试怎么写

这篇测试要连真实的 MySQL，我没换 H2：分页改写、逻辑删除过滤、乐观锁自增全是数据库层面的行为，换内存库就是「测了个仿制品」。为依赖外部数据库我做了两件小事。

第一件，上下文创建阶段不碰数据库。Hikari 建池默认立刻拿连接，拿不到抛异常，Spring 上下文创建失败，测试类一片红。`initialization-fail-timeout` 设 `-1` 改懒加载。

```yaml
spring:
  datasource:
    hikari:
      initialization-fail-timeout: -1
      connection-timeout: 3000
```

第二件，用例执行前探测一次，连不上整体跳过。

```java
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
```

没配 MySQL 的机器上跑 `mvn test`，看到的是 `Skipped`，不是一堆红色。类上加了 `@Transactional`，用例结束事务回滚。

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderApiIntegrationTest {
```

不回滚的话演示数据跑几次就乱，还得每次重置；我实测跑完库里还是 7 行 1 条已删除，一行没多。

```bash
mvn test
```

```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

24 个用例里有几个是为踩过的坑写的回归测试。创建接口必须返回 `version`，那个洞就是它发现的。

```java
body.extractingPath("$.version").asNumber().isEqualTo(0);
```

这条把「时间戳与落库值一致」钉住。

```java
assertThat(created.createdAt()).isEqualTo(reloaded.createdAt());
assertThat(created.createdAt().getNano() % 1_000_000).isZero();
```

这条最值钱，同时守着 `autoResultMap` 和 `Jackson3TypeHandler` 两个配置。

```java
// 写入走的是实体上的 typeHandler，读取要靠 autoResultMap 生成的 resultMap，
// 少配任何一个，这里拿到的都是 null
assertThat(reloaded.items()).hasSize(2);
```

## 避坑指南

这部分是我实打实踩到的，按痛苦程度排序。

**坑一，`strictInsertFill` 对没标 `fill` 的字段静默跳过。** 不报错：`version` 标了 `@Version` 没标 `@TableField(fill = FieldFill.INSERT)`，填充那行白写，响应少一个字段。我一开始怀疑 Jackson 过滤了 null，看 SQL 日志才发现插入语句里没有 `version` 列。教训：**遇到「响应里少一个字段」先看 SQL，别先怀疑序列化。**

**坑二，JSON 列读取出来是 null，而且不报错。** 标了 typeHandler 却没开 `autoResultMap = true`，写入正常读取永远 null。手写 XML 用 `resultType` 同样映射不了，规律：**带自定义 typeHandler 的字段，必须落到某个 resultMap 上**。

**坑三，`JacksonTypeHandler` 在 Boot 4 下是坏的。** 编译能过，运行期报 `NoClassDefFoundError`。`com.fasterxml` 开头是 Jackson 2，`tools.jackson` 开头是 Jackson 3，Boot 4 只能用后者，且得 3.5.17 往上的版本。

**坑四，时间戳精度不匹配。** `LocalDateTime.now()` 带纳秒，`DATETIME(3)` 存到毫秒，不截断则创建接口返回一个值、查出来另一个。和第 1 篇「`ProblemDetail` 字段按字母序输出」同类，客户端等值比较踩空。判断标准是**写入返回的值和随后读到的值必须一致**。

**坑五，分页拦截器的顺序。** 分页插件放拦截器链最后，官方文档明确写的，否则 `COUNT` 可能算错。

**坑六，乐观锁是可选的。** `@Version` 传 null 就是无保护覆盖写。接口文档没写清楚，调用方会默认有并发保护。`OrderUpdateRequest` 的 `version` 上写了文档注释说明区别，不能容忍覆盖写就改成必填。

**坑七，`ORDER BY` 的列名没法参数化。** 有人直接把客户端字符串丢进 `orderBy`，官方文档专门提醒过。排序字段必须走白名单，这不算防御性编程，这是必答题。

## 小结

选型上说我的看法：单表为主、想少写 SQL 的 CRUD 服务，MyBatis-Plus 是稳稳的赚，但要用「能力从注解上读」的思路去用它，标了它才管，而且大多数时候不告诉你。动手清单四条：starter 带 boot4 后缀、分页单独引 jsqlparser、MapperScan 用 mybatis 的包、JSON 列配 Jackson3TypeHandler 加 autoResultMap。分页插件永远放拦截器链尾，手写 SQL 的 `deleted` 过滤自己负责。实体注解一条条对着「不写会怎样」过一遍，比出问题后再查日志省时间。

没解决的事记两条：`DbType` 换成 MySQL 以外的方言后，分页改写行为我没有验证；多租户和数据权限两个插件只在文档里读过，没在工程里注册过，等有真实场景我再补一篇实测。

## 参考链接

- [MyBatis-Plus 官方文档](https://baomidou.com/)
- [MyBatis-Plus - Spring Boot 4 Starter 安装指引](https://baomidou.com/getting-started/install/)
- [MyBatis 3 中文文档](https://mybatis.org/mybatis-3/zh_CN/)
- [Spring Boot 4.1 Reference - SQL 数据库（含脚本初始化）](https://docs.spring.io/spring-boot/4.1/reference/data/sql.html)
