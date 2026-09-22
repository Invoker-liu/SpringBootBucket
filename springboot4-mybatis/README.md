# springboot4-mybatis

Spring Boot 4 全家桶教程 · 第 2 篇配套工程：**用 MyBatis-Plus 接管第 1 篇的订单接口，数据从内存搬到 MySQL**。

接口路径、状态码、请求与响应结构跟第 1 篇完全一致，换掉的只有持久层：
第 1 篇的 `OrderRepository` 内存实现，在这里被 `OrderMapper` 取代。Controller、DTO、
状态机、错误处理这些都没动——这也是把仓储单独抽一层的价值所在。

## 一、这个工程演示了什么

| 主题 | 具体做法 |
|---|---|
| 通用 CRUD | `BaseMapper<T>` 直接提供增删改查，单表操作一行 SQL 都不用写 |
| 条件构造器 | `LambdaQueryWrapper` 用方法引用指定列，属性改名编译期就能发现 |
| 条件优先级 | 关键字同时匹配订单号与姓名时，用 `.and(...)` 把两个 `like` 包成一组 |
| 分页插件 | `PaginationInnerInterceptor` 自动改写 `LIMIT` 并追加 `COUNT`，业务代码不碰分页 SQL |
| 自动填充 | `MetaObjectHandler` 回写 `created_at` / `updated_at` / `version`，业务代码不设时间戳 |
| 乐观锁 | `OptimisticLockerInnerInterceptor` 把 `UPDATE` 改写成 `WHERE version = ?`，版本对不上就是 409 |
| 逻辑删除 | `@TableLogic` 让 `deleteById` 变成 `UPDATE ... SET deleted = 1`，所有框架生成的查询自动过滤 |
| 手写 SQL | 回收站与聚合统计走 XML，正好用来说明"逻辑删除的过滤只管框架生成的 SQL" |
| JSON 列 | `List<OrderItem>` 序列化进一个 `VARCHAR` 列，用 `Jackson3TypeHandler` 而非 Jackson 2 时代的 `JacksonTypeHandler` |
| 结果映射 | 自定义 typeHandler 必须配合 `@TableName(autoResultMap = true)`，手写 XML 则要用 `<resultMap>` 而非 `resultType` |
| 显式更新策略 | `FieldStrategy.ALWAYS` 让 `remark: null` 能真正把列清空，而不是被当成"未提交" |
| 嵌套校验 | 列表字段上标 `@Valid`，明细里每条商品的约束才会生效，错误路径形如 `items[0].price` |
| 测试 | 真连 MySQL 的集成测试，`@Transactional` 自动回滚；库不可用时整体跳过而不是报错 |

## 二、环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Spring Boot | 4.1.1（Spring Framework 7.0.9、Tomcat 11.0.24、Jackson 3.1.5） |
| MyBatis-Plus | 3.5.17（`mybatis-spring` 4.0.0、`mybatis` 3.5.19） |
| MySQL | 8.0+（本工程在 8.4 上验证） |

需要一台可连的 MySQL。第 1 篇那种"克隆下来就能跑"的体验在这一篇没有了——
本篇的目的就是让真实数据库参与进来。

## 三、准备数据库

建表脚本在 `src/main/resources/sql/schema.sql`，会建库 `springboot4_mybatis`、
建表 `t_order`，并写入 6 条覆盖全部 5 个状态的演示数据。脚本可重复执行，每次都是删表重建。

```bash
mysql -h 127.0.0.1 -P 3306 -uroot -p < src/main/resources/sql/schema.sql
```

连接信息通过环境变量覆盖，不写死在代码里：

| 变量 | 默认值 |
|---|---|
| `DB_HOST` | `192.168.1.97` |
| `DB_PORT` | `3306` |
| `DB_NAME` | `springboot4_mybatis` |
| `DB_USER` | `root` |
| `DB_PASSWORD` | `root123456` |

> 默认值指向的是作者的局域网地址，直接跑之前请先把 `DB_HOST` 改掉，
> 或者在 `application.yml` 里改默认值。库名带 `springboot4_` 前缀，不会和别的工程撞。

表结构：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | 主键 |
| `order_no` | `VARCHAR(32)` | 订单号，服务端生成，唯一索引 |
| `customer_name` / `customer_phone` | `VARCHAR` | 客户信息 |
| `total_amount` | `DECIMAL(12,2)` | 金额 |
| `status` | `VARCHAR(16)` | 存枚举名，不是序号 |
| `remark` | `VARCHAR(255) NULL` | 备注 |
| `items` | `VARCHAR(1000) NULL` | 订单明细，存 JSON 数组，由 `Jackson3TypeHandler` 读写 |
| `created_at` / `updated_at` | `DATETIME(3)` | 只到毫秒，由 `MetaObjectHandler` 填充 |
| `deleted` | `TINYINT DEFAULT 0` | 逻辑删除标记 |
| `version` | `INT DEFAULT 0` | 乐观锁版本号 |

## 四、运行

```bash
cd springboot4-mybatis

# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package
java -jar target/springboot4-mybatis-1.0.0.jar
```

启动日志里看到这一行即成功：

```
Tomcat started on port 8080 (http) with context path '/'
Started MybatisApplication in 2.31 seconds
```

## 五、接口清单

| 方法 | 路径 | 说明 | 成功状态码 |
|---|---|---|---|
| POST | `/api/orders` | 创建订单，订单号由服务端生成 | 201 + `Location` |
| GET | `/api/orders/{id}` | 按主键查询 | 200 |
| GET | `/api/orders` | 分页 + 条件查询 | 200 |
| GET | `/api/orders/deleted` | 回收站：已被逻辑删除的订单 | 200 |
| GET | `/api/orders/stats` | 按状态聚合统计 | 200 |
| PUT | `/api/orders/{id}` | 整体替换业务字段（幂等） | 200 |
| PATCH | `/api/orders/{id}/status` | 状态流转 | 200 |
| DELETE | `/api/orders/{id}` | 逻辑删除 | 204 |

### 查询参数

| 参数 | 默认值 | 约束 |
|---|---|---|
| `keyword` | 无 | 模糊匹配订单号或客户姓名 |
| `status` | 无 | `CREATED` / `PAID` / `SHIPPED` / `COMPLETED` / `CANCELLED` |
| `page` | `0` | ≥ 0 |
| `size` | `10` | 1 ~ 100 |
| `sort` | `createdAt,desc` | 字段取自白名单，方向为 `asc`/`desc` |

`page` 从 `0` 开始是**对外契约**，而 MyBatis-Plus 的 `Page` 从 `1` 开始。
这个差异只在 `OrderService.page()` 里的一行消化掉，不让它渗透到接口上。

### 状态机

```
CREATED ──> PAID ──> SHIPPED ──> COMPLETED
   │          │
   └──────────┴──────> CANCELLED
```

状态机写在 `OrderStatus` 枚举里，用 switch 表达式实现，`enums.ordinal()` 不参与任何判断——
往枚举中间插一个状态，不会把已有状态的语义挪位。

## 六、手工测试

以下响应都是在本机真实跑出来的，可以直接对照。

### 创建订单

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

{"id":7,"orderNo":"ORD2026091768809955","customerName":"熊大","customerPhone":"138****8000",
 "totalAmount":299.50,"status":"CREATED","statusLabel":"已创建","remark":"演示下单",
 "items":[{"name":"机械键盘","quantity":1,"price":299.50},{"name":"腕托","quantity":2,"price":25.00}],
 "version":0,"createdAt":"2026-09-17T23:40:56.715","updatedAt":"2026-09-17T23:40:56.715"}
```

`orderNo`、`status`、`createdAt`、`updatedAt`、`version` 都不在请求体里，全是服务端填的。
插入语句由 MyBatis-Plus 生成，日志里能看到 `items` 已经变成了一段 JSON 字符串：

```
==>  Preparing: INSERT INTO t_order ( order_no, customer_name, customer_phone, total_amount,
              status, remark, created_at, updated_at, items, version ) VALUES ( ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )
==>  Parameters: ORD2026091770249686(String), 熊大(String), 13800138000(String), 299.50(BigDecimal),
              CREATED(String), 演示下单(String),
              2026-09-17T23:43:22.327(LocalDateTime), 2026-09-17T23:43:22.327(LocalDateTime),
              [{"name":"机械键盘","quantity":1,"price":299.50},{"name":"腕托","quantity":2,"price":25.00}](String),
              0(Integer)
<==    Updates: 1
```

`items` 那一项的类型标注是 `(String)`，说明整份列表在进 JDBC 之前就已经被序列化了。
另外注意列清单里没有 `deleted`，实体上这个字段是 null，插入时被跳过，落库靠的是列的 `DEFAULT 0`。

### 分页查询

```bash
curl -s "http://localhost:8080/api/orders?size=3&sort=id,asc"
```

```json
{"list":[{"id":1,...},{"id":2,...},{"id":3,...}],"page":0,"size":3,"total":6,"totalPages":2}
```

打开 SQL 日志能看到分页插件做了两件事：先跑一条自动生成的 `COUNT`，再把原查询改写成带 `LIMIT` 的语句。
两条 SQL 都是自动生成的：

```
==>  Preparing: SELECT COUNT(*) AS total FROM t_order
                  WHERE deleted = 0 AND ((order_no LIKE ? OR customer_name LIKE ?))
==>  Parameters: %ORD%(String), %ORD%(String)

==>  Preparing: SELECT id,order_no,customer_name,customer_phone,total_amount,status,remark,created_at,updated_at,items,deleted,version
                  FROM t_order
                  WHERE deleted=0 AND ((order_no LIKE ? OR customer_name LIKE ?))
                  ORDER BY id ASC LIMIT ?
==>  Parameters: %ORD%(String), %ORD%(String), 2(Long)
```

三点值得注意。关键字被 `.and(...)` 包进了一对括号，少了它后面的过滤条件会被 `OR` 的优先级吃掉。
`deleted = 0` 是逻辑删除自动加上的。第 0 页只出 `LIMIT ?` 没有偏移量，翻到后面才会变成 `LIMIT ?,?`。

### 乐观锁

```bash
# 先拿到当前版本号
curl -s http://localhost:8080/api/orders/7 | grep -o '"version":[0-9]*'

# 带上正确版本号更新，200，版本号变成 1
curl -i -X PUT http://localhost:8080/api/orders/7 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":888.00,"remark":"改了金额","version":0}'

# 再拿已经过期的版本号 0 更新，409
curl -i -X PUT http://localhost:8080/api/orders/7 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":666.00,"remark":"并发写入","version":0}'
```

```json
{
  "detail": "订单已被其他人修改，请刷新后重试",
  "instance": "/api/orders/7",
  "status": 409,
  "title": "业务规则不满足",
  "type": "urn:problem-type:business-rule-violation"
}
```

对应的 SQL 被改写成了这样，版本号出现在两个位置：

```
==>  Preparing: UPDATE t_order SET customer_name=?, customer_phone=?, total_amount=?, remark=?,
                  updated_at=?, version=? WHERE id=? AND version=? AND deleted=0
==>  Parameters: 熊大(String), 13800138000(String), 199.00(BigDecimal), 改价(String),
                  2026-09-17T23:43:23.050(LocalDateTime), 1(Integer), 7(Long), 0(Integer)
<==    Updates: 1
```

`SET` 里的 `version` 绑定的是**新值 1**，`WHERE` 里的绑定的是**旧值 0**。
新版本号是 MyBatis-Plus 在 Java 侧算好之后绑上去的，不是写成 `version = version + 1` 交给数据库算。
所以这条 UPDATE 一旦被别人抢先改过，`WHERE version = 0` 就匹配不上，`Updates` 变成 0，
服务层据此抛出 409。

**版本号是可选的**：不传 `version` 时乐观锁不生效，直接覆盖。这是把"要不要并发保护"
的决定权交给客户端；如果希望强制，把 `OrderUpdateRequest.version` 上的注解改成 `@NotNull` 即可。

### 清空备注

```bash
curl -i -X PUT http://localhost:8080/api/orders/7 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":888.00,"remark":null}'
```

响应里的 `remark` 直接消失了（全局配置了非空字段不参与序列化），要确认它是不是真的落到库里，
得直接查表：

```sql
SELECT id, version, IF(remark IS NULL,'NULL','有值') AS remark_state, deleted
FROM t_order WHERE id IN (3, 7);

+----+---------+--------------+---------+
| id | version | remark_state | deleted |
+----+---------+--------------+---------+
|  3 |       0 | 有值         |       1 |
|  7 |       1 | NULL         |       0 |
+----+---------+--------------+---------+
```

`id=7` 的 `remark` 是真正的 `NULL`。这一列在实体上标了
`@TableField(updateStrategy = FieldStrategy.ALWAYS)`——默认策略下 null 表示"本次不更新这一列"，
"清空备注"这个操作就永远做不成。

同时这张表也说明了逻辑删除：`id=3` 的行还在，只是 `deleted` 变成了 1，
其余字段一个没动。

### 状态流转

```bash
# 合法：CREATED -> PAID，200
curl -i -X PATCH http://localhost:8080/api/orders/7/status \
  -H "Content-Type: application/json" -d '{"status":"PAID"}'

# 非法：PAID -> CREATED，422
curl -i -X PATCH http://localhost:8080/api/orders/7/status \
  -H "Content-Type: application/json" -d '{"status":"CREATED"}'
```

```json
{
  "detail": "订单不允许从 已支付 变更为 已创建",
  "instance": "/api/orders/7/status",
  "status": 422,
  "title": "业务规则不满足",
  "type": "urn:problem-type:business-rule-violation"
}
```

### 逻辑删除与回收站

```bash
curl -i -X DELETE http://localhost:8080/api/orders/3   # 204，无响应体
curl -i     http://localhost:8080/api/orders/3         # 404，查不到了
curl -s     http://localhost:8080/api/orders/deleted   # 200，回收站里查得到
```

```json
[{"id":3,"orderNo":"ORD20260901000003","customerName":"张三","customerPhone":"139****9000",
  "totalAmount":68.80,"status":"SHIPPED","statusLabel":"已发货","remark":"演示数据","version":0,
  "createdAt":"2026-09-17T23:33:28.356","updatedAt":"2026-09-17T23:33:40.391"}]
```

`deleteById` 执行的确实是一条 UPDATE，而且审计字段也跟着更新了：

```
==>  Preparing: UPDATE t_order SET updated_at=?, deleted=1 WHERE id=? AND deleted=0
==>  Parameters: 2026-09-17T23:43:23.226(LocalDateTime), 3(Long)
<==    Updates: 1
```

`deleted=0` 这个条件是框架加的，所以重复删除第二次会 `Updates: 0`，服务层据此返回 404。

回收站能查到已删除的行，靠的是手写 XML。这一条务必记住：

> `@TableLogic` 的过滤只加在 MyBatis-Plus 生成的 SQL 上，**手写 XML 不会被追加**。
> 它既是回收站能查到已删除数据的原因，也意味着手写查询必须自己写上 `AND deleted = 0`，
> 否则已删除的数据会重新冒出来。

### 聚合统计

```bash
curl -s http://localhost:8080/api/orders/stats
```

```json
[{"orderCount":1,"status":"CANCELLED","statusLabel":"已取消","totalAmount":199.00},
 {"orderCount":1,"status":"COMPLETED","statusLabel":"已完成","totalAmount":4599.00},
 {"orderCount":1,"status":"CREATED","statusLabel":"已创建","totalAmount":299.50},
 {"orderCount":3,"status":"PAID","statusLabel":"已支付","totalAmount":3056.00}]
```

演示数据里 `id=3` 是 `SHIPPED`，已被逻辑删除，所以结果里没有 `SHIPPED` 这一项——
手写 SQL 同样显式写了 `WHERE deleted = 0`。

### 参数校验失败

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"","customerPhone":"123","totalAmount":-1}'
```

```json
{
  "detail": "请求体字段校验未通过，逐字段原因见 errors",
  "instance": "/api/orders",
  "status": 400,
  "title": "请求体校验失败",
  "errors": [
    {"message":"订单金额必须大于 0","field":"totalAmount"},
    {"message":"客户手机号格式不正确","field":"customerPhone"},
    {"message":"客户姓名不能为空","field":"customerName"}
  ]
}
```

`errors` 数组里元素的顺序由校验器决定，每次运行都可能不同，对象内部的键顺序也一样。
客户端应当按 `field` 去查，不要依赖下标。

## 七、运行测试

```bash
mvn test
```

预期结果：

```
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 技术 | 覆盖内容 |
|---|---|---|
| `OrderApiIntegrationTest` | `@SpringBootTest` + `MockMvcTester` + 真实 MySQL | 24 个用例：CRUD、分页与排序、自动填充、乐观锁、逻辑删除与回收站、JSON 列的往返映射、聚合一致性、problem 文档结构 |

关于测试有三点说明：

1. **测试直连真实 MySQL**，不换成 H2。本篇要验证的分页改写、`@TableLogic` 过滤、乐观锁自增，
   都是数据库层面的行为，换成内存库就成了"测了个仿制品"。
2. **库连不上时整体跳过，而不是报错**。`application-test.yml` 把 Hikari 改成懒加载，
   上下文创建阶段不碰数据库；用例执行前再探测一次，连不上就直接 skip。
   所以在一台没配 MySQL 的机器上跑 `mvn test` 会看到 `Skipped`，不会看到一片红。
3. **类上有 `@Transactional`，每个用例结束自动回滚**。测试数据不会残留在演示数据里，
   也不需要每次跑之前先重置数据库。

## 八、工程结构

```
src/main/java/com/xncoding/mybatis/
├── MybatisApplication.java          启动类
├── controller/
│   └── OrderController.java         资源入口，含 sort 表达式解析
├── service/
│   └── OrderService.java            业务规则 + 条件构造器组装，直接持有 Mapper
├── mapper/
│   └── OrderMapper.java             继承 BaseMapper，另加两个自定义查询
├── domain/
│   ├── Order.java                   实体（可变 POJO，注解齐全）
│   ├── OrderItem.java               订单明细，整列表序列化成 JSON 存一列
│   ├── OrderStatus.java             状态枚举 + 状态机
│   └── OrderPageQuery.java          查询条件，含排序白名单与 size 上限
├── dto/                             请求体与响应体，与第 1 篇一致
├── handler/
│   └── AuditFieldHandler.java       MetaObjectHandler，回写审计字段
├── config/
│   └── MybatisPlusConfig.java       注册乐观锁与分页拦截器
└── exception/                       404 / 409 / 422 / 400 统一输出
    ├── ResourceNotFoundException.java
    ├── BusinessException.java
    └── GlobalExceptionHandler.java

src/main/resources/
├── application.yml                  数据源、MyBatis-Plus 全局配置
├── mapper/OrderMapper.xml           回收站查询（resultMap）与聚合统计
└── sql/schema.sql                   建库建表 + 演示数据
```

`OrderService` 刻意没有继承 `ServiceImpl<OrderMapper, Order>`。`IService` 会往业务接口上挂
三十多个方法，其中九成本工程用不到，却都成了公开契约。团队习惯那一套的话，用它也没问题。

## 九、从 Spring Boot 2.0 迁过来要注意什么

1. **starter 必须带 `boot4` 后缀**。`mybatis-plus-spring-boot-starter` 在 Boot 4 下不工作，
   要用 `mybatis-plus-spring-boot4-starter`。它已经带了 `spring-boot-starter-jdbc` 与 HikariCP，
   不用再单独引。
2. **分页插件要单独加依赖**。自 MyBatis-Plus 3.5.9 起 `PaginationInnerInterceptor` 从核心包
   分离，只引 starter 的话这个类根本找不到，必须再加 `mybatis-plus-jsqlparser`。
   建议直接导入官方 `mybatis-plus-bom`，避免两个包版本错位。
3. **`MapperScan` 换包了**。`com.baomidou.mybatisplus.annotation.MapperScan` 在 3.5.17 里
   已经不存在，同包路径下编译会直接失败。改用 `org.mybatis.spring.annotation.MapperScan`，
   或者干脆在每个接口上标 `@Mapper`——本工程用的是后者，省一个配置类。
4. **分页拦截器必须最后添加**。`MybatisPlusInterceptor.addInnerInterceptor` 的顺序有语义，
   分页要放在最后一个，否则它拿到的 SQL 已经不是最终形态，`LIMIT` 会加错位置。
5. **`FieldStrategy` 决定 null 怎么处理**。默认 `NOT_NULL`（null 表示不更新这一列），
   想把 null 真正写进数据库，得在字段上标 `updateStrategy = FieldStrategy.ALWAYS`。
6. **`strictInsertFill` 有前提**。它只填充标了 `@TableField(fill = FieldFill.INSERT)` 的字段，
   漏标会被**静默跳过**——不报错、不打日志，只是响应里少一个字段。`version` 这种想插入时写 0
   的字段也得标上 `FieldFill.INSERT`。
7. **注意时间字段精度**。`LocalDateTime.now()` 带纳秒，而 `DATETIME(3)` 只存到毫秒。
   不在填充时截断，创建接口返回的时间戳和随后查出来的就对不上。
8. **乐观锁是可选的**。实体上的 `@Version` 只有在传进来的对象带版本号时才生效，
   传 null 就是一次普通覆盖更新。
9. **逻辑删除配置搬进了 `application.yml`**。不再需要 `@Bean` 方式注册
   `ISqlInjector`，写 `mybatis-plus.global-config.db-config.logic-delete-value` 即可。
10. **`DbType` 要显式声明**。`new PaginationInnerInterceptor(DbType.MYSQL)`，
    别指望自动探测——多数据源场景下它会认错方言。
11. **JSON 列的 typeHandler 换人了**。Jackson 2 时代的 `JacksonTypeHandler` 引用的是
    `com.fasterxml.jackson.databind.ObjectMapper`，而 Spring Boot 4 只带 Jackson 3
    （`tools.jackson`），继续用它运行时会直接找不到类。3.5.17 新增了
    `Jackson3TypeHandler`，它才是 Boot 4 下该用的那个。这个类在 3.5.13 里还没有。
12. **自定义 typeHandler 必须打开 `autoResultMap`**。实体上只写
    `@TableField(typeHandler = ...)` 是不够的，MyBatis-Plus 默认走自动映射，不认 typeHandler。
    要在 `@TableName` 上加 `autoResultMap = true`，框架才会生成带 typeHandler 的 resultMap。
    漏掉它的症状很隐蔽：写入正常，读取永远是 null，而且不报错。
13. **手写 XML 里要用 `resultMap` 而不是 `resultType`**。同一个原因，自动映射不看 `@TableField`，
    带自定义 typeHandler 的列在 `resultType` 下无法完成转换。

## 十、许可

MIT License，作者 Xiong Neng。
