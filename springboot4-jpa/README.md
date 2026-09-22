# springboot4-jpa

Spring Boot 4 全家桶教程 · 第 3 篇配套工程：**换成 Spring Data JPA + Hibernate 7，把第 2 篇的订单接口重写一遍**。

接口路径、状态码、请求与响应结构跟前两篇完全一致，换掉的还是只有持久层。
第 1 篇是内存里的 `OrderRepository`，第 2 篇是 `OrderMapper`，这一篇是 JPA 仓储。
三篇放在一起看，改动量最大的地方恰恰不是 Controller——状态机、DTO、错误响应一个字符都没动。

## 一、这个工程演示了什么

| 主题 | 具体做法 |
|---|---|
| 实体映射 | `@Entity` + `@Column` 声明列名与约束，`@Enumerated(EnumType.STRING)` 显式指定枚举存名字 |
| 关联映射 | `@OneToMany(mappedBy = ...)` / `@ManyToOne(LAZY)` 双向一对多，由一方维护关联 |
| 级联与孤儿 | `cascade = CascadeType.ALL` + `orphanRemoval = true`，明细的保存与移除跟着订单走 |
| 审计字段 | `@EnableJpaAuditing` + `@CreatedDate` / `@LastModifiedDate`，时间戳由框架回填 |
| 审计精度 | 自定义 `DateTimeProvider` 把时间截到毫秒，让响应里的时间戳和落库值一致 |
| 派生查询 | `findByOrderNo` / `countByStatus`，方法名即查询，一行实现都不写 |
| JPQL | `@Query` 写实体和属性名，聚合结果用构造器表达式直接映射到 record |
| 原生 SQL | `nativeQuery = true` 写表名列名，用来演示 `@SoftDelete` 管不到它 |
| 接口投影 | 只取四列的摘要查询，返回接口而非实体，SQL 里就只有那四列 |
| Specification | 动态条件查询，为 null 的条件自动忽略，4.x 的 `Specification.allOf` 写法 |
| 分页 | `Pageable` + 统一的分页响应体，不把 Spring Data 的 `Page` 直接当契约 |
| 乐观锁 | `@Version`，以及它的边界——跨 HTTP 往返的版本校验必须自己在服务层做 |
| 软删除 | `@SoftDelete`，`delete` 生成的是 `UPDATE ... SET deleted=true` |
| 关联抓取 | `@EntityGraph` 解决单对象 N+1，`hibernate.default_batch_fetch_size` 解决集合批量抓取 |
| 事务边界 | 写方法可读写、读方法 `readOnly`，`open-in-view` 关掉后实体转 DTO 必须在事务内 |
| 测试 | `@DataJpaTest` 切片测试 + `@SpringBootTest` 集成测试，都真连 MySQL |

## 二、环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Spring Boot | 4.1.1（Spring Framework 7.0.9） |
| Hibernate ORM | 7.4.5.Final |
| Jakarta Persistence | 3.2.0 |
| Spring Data BOM | 2026.0.1 |
| MySQL | 8.0+（本工程在 8.4 上验证） |

Hibernate 的版本号值得留意：网上讲 Spring Boot 4 + JPA 的文章里，
「Hibernate 7.1」「Hibernate 7.2」都出现过，实际 4.1.1 的 BOM 锁的是 **7.4.5.Final**。
版本号这种事别抄二手资料，`mvn dependency:tree` 跑一遍最快。

需要一台可连的 MySQL。

## 三、准备数据库

建表脚本在 `src/main/resources/sql/schema.sql`，会建库 `springboot4_jpa`、
建两张表 `t_order` 与 `t_order_item`，并写入 5 条订单 + 5 条明细的演示数据。
脚本可重复执行，每次都是删表重建。

```bash
mysql -h 127.0.0.1 -P 3306 -uroot -p < src/main/resources/sql/schema.sql
```

连接信息通过环境变量覆盖，不写死在代码里：

| 变量 | 默认值 |
|---|---|
| `DB_HOST` | `192.168.1.97` |
| `DB_PORT` | `3306` |
| `DB_NAME` | `springboot4_jpa` |
| `DB_USER` | `root` |
| `DB_PASSWORD` | `root123456` |

> 默认值指向的是作者的局域网地址，直接跑之前请先把 `DB_HOST` 改掉，
> 或者在 `application.yml` 里改默认值。库名带 `springboot4_` 前缀，不会和别的工程撞。

**这里没有配 `spring.sql.init`。** 它是「应用启动时自动执行建表脚本」的机制，
而 `schema.sql` 里带着 `DROP TABLE`，配上它就会变成每次启动都清库。
建表脚本改成手动执行一次，这也是最常见的踩坑点之一。

表结构：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | 主键 |
| `order_no` | `VARCHAR(32)` | 订单号，服务端生成，唯一索引 |
| `customer_name` / `customer_phone` | `VARCHAR` | 客户信息 |
| `total_amount` | `DECIMAL(12,2)` | 金额 |
| `status` | `VARCHAR(16)` | 存枚举名，不是序号 |
| `remark` | `VARCHAR(255) NULL` | 备注 |
| `created_at` / `updated_at` | `DATETIME(3)` | 只到毫秒，由审计监听器填充 |
| `deleted` | `BIT(1) DEFAULT b'0'` | 软删除标记，与 Java 的 `boolean` 对应 |
| `version` | `INT DEFAULT 0` | 乐观锁版本号 |

明细表 `t_order_item` 结构类似，多一个 `order_id` 外键指向 `t_order`。

两处细节要对齐：`deleted` 用的是 `BIT(1)`，因为 Hibernate 把 Java 的 `boolean`
默认映射到 `BIT(1)`；`ddl-auto` 是 `validate`，列的类型对不上应用会直接启动失败。

## 四、运行

```bash
cd springboot4-jpa

# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package
java -jar target/springboot4-jpa-1.0.0.jar
```

启动日志里看到这一行即成功：

```
Tomcat started on port 8080 (http) with context path '/'
Started JpaApplication in 3.12 seconds
```

## 五、接口清单

| 方法 | 路径 | 说明 | 成功状态码 |
|---|---|---|---|
| POST | `/api/orders` | 创建订单，订单号由服务端生成 | 201 + `Location` |
| GET | `/api/orders/{id}` | 按主键查询，带明细 | 200 |
| GET | `/api/orders` | 分页 + 条件查询 | 200 |
| GET | `/api/orders/stats` | 按状态聚合统计（JPQL 构造器表达式） | 200 |
| GET | `/api/orders/summary` | 订单摘要（接口投影，只查四列） | 200 |
| GET | `/api/orders/count` | 某状态有效订单数（原生 SQL） | 200 |
| GET | `/api/orders/expensive` | 金额不低于指定值的订单（JPQL） | 200 |
| PUT | `/api/orders/{id}` | 整体替换业务字段（幂等） | 200 |
| PATCH | `/api/orders/{id}/status` | 状态流转 | 200 |
| DELETE | `/api/orders/{id}` | 软删除 | 204 |

### 查询参数

| 参数 | 默认值 | 约束 |
|---|---|---|
| `keyword` | 无 | 模糊匹配订单号或客户姓名 |
| `status` | 无 | `CREATED` / `PAID` / `SHIPPED` / `COMPLETED` / `CANCELLED` |
| `page` | `0` | ≥ 0 |
| `size` | `10` | 1 ~ 100 |
| `sort` | `createdAt,desc` | 字段取自白名单，方向为 `asc`/`desc` |

**`sort` 传的是实体属性名，不是数据库列名。** 这是本篇和上一篇最容易被忽略的差别：
MyBatis-Plus 的 `OrderItem` 拼的是 SQL 片段，写 `created_at`；
Spring Data 的 `Sort` 走属性路径，写 `created_at` 会抛
`PropertyReferenceException: No property 'created' found for type 'Order'`。
报错里之所以是 `created` 而不是 `created_at`，是因为下划线在 Spring Data 里是**嵌套属性的分隔符**，
它把这个字符串拆成了「`created` 下面的 `at`」。

### 状态机

```
CREATED ──> PAID ──> SHIPPED ──> COMPLETED
   │          │
   └──────────┴──────> CANCELLED
```

状态机写在 `OrderStatus` 枚举里，用 switch 表达式实现，`ordinal()` 不参与任何判断。

## 六、手工测试

以下响应都是在本机真实跑出来的，可以直接对照。应用用的是默认端口 `8080`。

### 创建订单

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":299.50,"remark":"演示下单",
       "items":[{"productName":"机械键盘","price":299.50,"quantity":1},{"productName":"腕托","price":25.00,"quantity":2}]}'
```

```http
HTTP/1.1 201
Location: http://127.0.0.1:8080/api/orders/6
Content-Type: application/json

{"id":6,"orderNo":"ORD202609181901110307456","customerName":"熊大","customerPhone":"138****8000",
 "totalAmount":299.50,"status":"CREATED","statusLabel":"已创建","remark":"演示下单",
 "items":[{"id":6,"productName":"机械键盘","price":299.50,"quantity":1,"amount":299.50},
          {"id":7,"productName":"腕托","price":25.00,"quantity":2,"amount":50.00}],
 "version":0,"createdAt":"2026-09-18T19:01:11.128","updatedAt":"2026-09-18T19:01:11.128"}
```

一次请求只发了一条 `INSERT`，然后 Hibernate 靠 `GeneratedKeyHolder` 把主键取回来，
再按 `CascadeType.ALL` 把两条明细插进去。审计字段和版本号都不是业务代码设的。

紧接着按 id 再查一次，返回的 `createdAt` 是 `2026-09-18T19:01:11.128`，和创建时**一模一样**。
这个一致不是自动的：`AuditingEntityListener` 默认取 `LocalDateTime.now()`，带完整纳秒，
而 `created_at` 是 `DATETIME(3)` 只存到毫秒。不处理的话创建接口返回 `...19.3754211`、
重查返回 `...19.375`，同一个字段两个值。做法是在启动类里注册一个自定义 `DateTimeProvider`：

```java
@Bean
public DateTimeProvider auditingDateTimeProvider() {
    return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
}
```

### 乐观锁

```bash
# 带上正确版本号更新，200，版本号变成 1
curl -i -X PUT http://localhost:8080/api/orders/6 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":888.00,"remark":"改了金额",
       "items":[{"productName":"机械键盘","price":888.00,"quantity":1}],"version":0}'

# 再拿已经过期的版本号 0 更新，409
curl -i -X PUT http://localhost:8080/api/orders/6 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":666.00,"remark":"并发写入","version":0}'
```

```json
{
  "detail": "订单已被其他人修改，请刷新后重试（当前版本 1，提交版本 0）",
  "instance": "/api/orders/6",
  "status": 409,
  "title": "业务规则不满足",
  "type": "urn:problem-type:business-rule-violation"
}
```

更新的 SQL 长这样，实体上的 `@Version` 会自己往 `WHERE` 里加一个版本条件：

```
update t_order set customer_name=?, customer_phone=?, order_no=?, remark=?, status=?,
       total_amount=?, updated_at=?, version=? where id=? and version=?
```

第二条请求之所以是 409，**不是这条 SQL 拦下的**——它根本没发出去。
服务层先比了一次版本号就抛了异常。这一点必须说清楚：

> `@Version` 保护的是「同一个事务里读出来又写回去」这种并发，
> 它拿的是持久化上下文里的快照版本去比。
> 而客户端传来的版本号是上一次请求的结果，中间隔着一次 HTTP 往返，
> Hibernate 根本不知道这回事。所以跨请求的版本校验只能自己在服务层做。

顺带一个容易写错的地方：手动调用实体的 `setVersion(...)` **不会**触发乐观锁校验。
Hibernate 生成 `UPDATE` 时用的是持久化上下文里的快照版本，
手动塞一个过期版本号并不会让更新失败，反而会把这个过期值直接写进库里。

**版本号是可选的**：不传 `version` 时跳过这次比对，退化成普通更新，版本号照常自增。

### 状态流转

```bash
# 合法：CREATED -> PAID，200
curl -i -X PATCH http://localhost:8080/api/orders/6/status \
  -H "Content-Type: application/json" -d '{"status":"PAID"}'

# 非法：PAID -> CREATED，422
curl -i -X PATCH http://localhost:8080/api/orders/6/status \
  -H "Content-Type: application/json" -d '{"status":"CREATED"}'
```

```json
{
  "detail": "订单不允许从 已支付 变更为 已创建",
  "instance": "/api/orders/6/status",
  "status": 422,
  "title": "业务规则不满足",
  "type": "urn:problem-type:business-rule-violation"
}
```

### 软删除

```bash
curl -i -X DELETE http://localhost:8080/api/orders/3   # 204，无响应体
curl -i     http://localhost:8080/api/orders/3         # 404，查不到了
```

```json
{"detail":"订单不存在：3","instance":"/api/orders/3","status":404,
 "title":"资源不存在","type":"urn:problem-type:resource-not-found",
 "resourceType":"订单","resourceId":3}
```

实体上标了 `@SoftDelete`，所以 `delete` 生成的是 UPDATE，一条 DELETE 都没有。
这是本轮验证脚本数出来的：整轮跑完 **delete 语句 0 条，update 语句 6 条**。
其中真正对应这次删除的那一条，连参数一起长这样：

```
update
    t_order
set
    deleted=true
where
    id=?
    and deleted=false
    and version=?
      binding parameter (1:BIGINT) <- [3]
      binding parameter (2:INTEGER) <- [0]
```

三处值得注意。第一，`set deleted=true`，Hibernate 7 直接写布尔字面量，
不是老版本那种 `deleted=1`。第二，`and deleted=false` 是框架自己加的条件，
所以重复删除第二次会匹配不到行，服务层据此返回 404。第三，`and version=?` 也在，
`@Version` 对软删除同样生效。

另外那 5 条 update 里，有 3 条是 `update t_order_item set deleted=true`，
对应「更新订单时替换明细」和「删除订单时级联到明细」两种孤儿移除。
`orphanRemoval` 遇上 `@SoftDelete`，生成的同样是 UPDATE 而不是 DELETE。

删完之后直接查库，那行还在：

```sql
SELECT id, order_no, status, deleted + 0 AS deleted, version FROM springboot4_jpa.t_order ORDER BY id;
```

```
+----+--------------------------+-----------+---------+---------+
| id | order_no                 | status    | deleted | version |
+----+--------------------------+-----------+---------+---------+
|  1 | ORD2026091801            | CREATED   |       0 |       0 |
|  2 | ORD2026091802            | PAID      |       0 |       0 |
|  3 | ORD2026091803            | SHIPPED   |       1 |       0 |
|  4 | ORD2026091804            | COMPLETED |       0 |       0 |
|  5 | ORD2026091805            | CREATED   |       0 |       0 |
|  6 | ORD202609181901110307456 | PAID      |       0 |       2 |
+----+--------------------------+-----------+---------+---------+
```

`id=3` 那一行还在，`deleted` 从 0 变成 1，物理行数仍然是 6，其余字段一个没动。

`deleted` 那一列要写成 `deleted + 0` 才看得见。它是 `BIT(1)`，直接 `SELECT deleted`
输出的是不可见的二进制，终端上看着像空列，很容易误判成「压根没删掉」。

### 原生 SQL 与软删除的关系

```bash
curl -s "http://localhost:8080/api/orders/count?status=SHIPPED"
```

```json
{"status":"SHIPPED","count":0}
```

演示数据里 `id=3` 正是 `SHIPPED`，刚被软删除，所以计数是 0。
这个 0 不是因为框架帮了忙，而是仓储里那条 SQL 自己写了 `deleted = 0`：

```java
@Query(value = "select count(*) from t_order where status = :status and deleted = 0",
       nativeQuery = true)
long countActiveByStatus(@Param("status") String status);
```

> `@SoftDelete` 的过滤只加在 Hibernate 自己生成的语句上。
> 原生 SQL 是「原样发给数据库」，框架一个字符都不会改。
> 忘了写 `deleted = 0`，被删掉的订单就会重新被算进来，而且不报任何错。

### 聚合统计

```bash
curl -s http://localhost:8080/api/orders/stats
```

```json
[{"status":"COMPLETED","count":1,"totalAmount":1288.00,"statusLabel":"已完成"},
 {"status":"CREATED","count":2,"totalAmount":644.90,"statusLabel":"已创建"},
 {"status":"PAID","count":2,"totalAmount":1017.00,"statusLabel":"已支付"},
 {"status":"SHIPPED","count":1,"totalAmount":88.50,"statusLabel":"已发货"}]
```

这个是 JPQL 的构造器表达式直接映射到 record，不用手写结果转换：

```java
@Query("""
        select new com.xncoding.jpa.dto.OrderStatusStat(
                   o.status, count(o), coalesce(sum(o.totalAmount), 0))
        from Order o
        group by o.status
        order by o.status
        """)
List<OrderStatusStat> statByStatus();
```

`coalesce` 不是装饰：某个状态一条数据都没有时，`sum()` 返回的是 null，
构造器参数声明的是 `BigDecimal`，没有这层兜底就直接 NPE。

### 接口投影

```bash
curl -s "http://localhost:8080/api/orders/summary?status=CREATED"
```

```json
[{"id":1,"orderNo":"ORD2026091801","status":"CREATED","totalAmount":599.00},
 {"id":5,"orderNo":"ORD2026091805","status":"CREATED","totalAmount":45.90}]
```

每行只有四个字段。`remark`、时间戳、明细集合一个都没出现，
因为生成的 SQL 里就只 `select` 了这四列——投影不是「查出来再丢掉」，
而是根本不查。

### N+1 与两种抓取策略

这是本篇最值得亲手跑一遍的地方。默认配置下，一次分页请求发出的 SQL 条数是这样的：

| 请求 | 默认配置 | `default_batch_fetch_size=50` |
|---|---|---|
| `GET /api/orders?size=1` | **3** | 3 |
| `GET /api/orders?size=3` | **5** | 3 |
| `GET /api/orders?size=5` | **7** | 3 |
| `GET /api/orders/1`（`@EntityGraph`） | 1 | 1 |

默认那一列是 `2 + 带明细的订单数`：1 条 count、1 条分页，然后每个订单各补一条查明细。
页越大涨得越多，这就是 N+1。日志里那段「现场」：

```
select ... from t_order o1_0 where o1_0.deleted=false order by o1_0.created_at desc limit ?, ?
select count(o1_0.id) from t_order o1_0 where o1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
```

加一行配置之后，条数变成恒定的 3，明细查询合并成了一条 `in`：

```
select ... from t_order_item i1_0
where i1_0.order_id in (?, ?, ..., ?) and i1_0.deleted=false
```

单对象查询走的是另一条路。`findWithItemsById` 上有 `@EntityGraph(attributePaths = "items")`，
生成的是 join，一次就够：

```
select o1_0.id, ..., i1_0.product_name, ...
from t_order o1_0
left join t_order_item i1_0 on o1_0.id=i1_0.order_id and i1_0.deleted=false
where o1_0.id=? and o1_0.deleted=false
```

三种做法的适用面不一样，别只记一个：

| 场景 | 做法 | 结果 |
|---|---|---|
| 按主键查单个对象 | `@EntityGraph` | 1 条 SQL，join 抓取 |
| 分页/列表查多个对象 | `hibernate.default_batch_fetch_size` | 恒定少量 SQL，`in` 批量抓 |
| 根本不需要明细 | 接口投影 / DTO 查询 | 直接不查那一列，最省 |

`@EntityGraph` 用在分页上会出问题：`left join` 加上 `limit`，
一页 10 个订单如果有 30 条明细，结果集是 30 行，分页就分错了。
列表场景要么用批量抓取，要么用投影，别硬套 `@EntityGraph`。

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
    {"field":"totalAmount","message":"订单金额必须大于 0"},
    {"field":"customerPhone","message":"客户手机号格式不正确"},
    {"field":"customerName","message":"客户姓名不能为空"}
  ]
}
```

嵌套校验也是有效的，明细列表上标了 `@Valid` 之后，错误路径形如 `items[0].price`。

## 七、运行测试

```bash
mvn test
```

预期结果：

```
Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 技术 | 覆盖内容 |
|---|---|---|
| `OrderRepositorySliceTest` | `@DataJpaTest` + 真实 MySQL | 12 个用例：派生查询、JPQL、原生 SQL、`@EntityGraph`、软删除、Specification、接口投影、审计字段精度、排序属性名 |
| `OrderApiIntegrationTest` | `@SpringBootTest` + `MockMvcTester` + 真实 MySQL | 26 个用例：CRUD、分页与排序、乐观锁、状态机、软删除、参数校验、problem 文档结构 |

关于测试有四点说明：

1. **测试直连真实 MySQL，不换成 H2。** 本篇要验证的软删除、乐观锁、`open-in-view` 关掉后的懒加载行为，
   都是数据库和事务层面的东西，换成内存库就成了「测了个仿制品」。
2. **库连不上时整体跳过，而不是报错。** `application-test.yml` 把 Hikari 改成懒加载，
   上下文创建阶段不碰数据库；用例执行前再探测一次，连不上就直接 skip。
   在一台没配 MySQL 的机器上跑 `mvn test` 会看到 `Skipped`，不会看到一片红。
3. **两个测试类分工不同。** 切片测试只加载 JPA 那一套自动配置，不起 Web 容器，
   跑得快、报错定位也准；集成测试负责参数校验、事务边界、问题详情响应结构这些
   「必须真的起一次上下文才说得清」的事。
4. **`@DataJpaTest` 的包名和以前不一样了。** 见下一节第 3 条。

## 八、工程结构

```
src/main/java/com/xncoding/jpa/
├── JpaApplication.java              启动类，@EnableJpaAuditing + 自定义 DateTimeProvider
├── controller/
│   └── OrderController.java         资源入口，含 sort 表达式解析
├── service/
│   └── OrderService.java            业务规则 + 事务边界 + 显式版本校验
├── repository/
│   ├── OrderRepository.java         派生查询 / JPQL / 原生 SQL / Specification / @EntityGraph
│   ├── OrderSummaryRepository.java  只读仓储，只暴露一个方法
│   ├── OrderSummary.java            接口投影
│   └── OrderSpecifications.java     动态条件构造
├── domain/
│   ├── Order.java                   聚合根，含审计字段、@Version、@SoftDelete
│   ├── OrderItem.java               明细，@ManyToOne 反向引用
│   ├── OrderStatus.java             状态枚举 + 状态机
│   └── OrderPageQuery.java          查询条件，含排序白名单
├── dto/                             请求体与响应体，与第 1、2 篇一致
└── exception/                       404 / 409 / 422 / 400 统一输出

src/main/resources/
├── application.yml                  数据源、JPA 配置
└── sql/schema.sql                   建库建表 + 演示数据
```

`OrderService` 里没有 `@Transactional` 之外的声明式魔法，事务边界都写在方法上：
写方法可读写，读方法 `readOnly = true`。后者不只是「表明意图」，
Spring 会把它传给 JDBC 连接，Hibernate 收到只读提示后会跳过脏检查、不保留持久化上下文快照，
批量读场景下省的是实实在在的内存和 CPU。

## 九、从 Spring Boot 3 / Hibernate 6 迁过来要注意什么

1. **JPA 自动配置拆成了三个模块。** Boot 3 时代 `JpaProperties`、
   `HibernateJpaAutoConfiguration`、`DataJpaRepositoriesAutoConfiguration` 都在
   `spring-boot-autoconfigure` 里，Boot 4 拆到了 `spring-boot-jpa`、`spring-boot-hibernate`、
   `spring-boot-data-jpa` 三个模块。老包名 `org.springframework.boot.autoconfigure.orm.jpa.*`
   在 4.1.1 已经不存在，照着旧文章写 import 直接编译不过。

2. **`@SoftDelete` 是 `@Where` 的替代品。** `@Where` 在 Hibernate 7 里已经没有了。
   想给实体加一个默认过滤条件，用 `org.hibernate.annotations.SQLRestriction`；
   而「软删除」这个具体场景，直接用 `@SoftDelete`，
   它会连 `delete` 的语义一起接过去，比自己写 `@SQLRestriction("deleted = 0")` 再手动改删除逻辑省事得多。
   `@SoftDelete` 还能配 `strategy`（`ACTIVE` / `DELETED` / `TIMESTAMP`）和 `columnName`，
   默认列名是 `deleted`，类型是布尔。

3. **测试注解换了包名，而且散在三个包里。**

   | 注解 | Boot 3 | Boot 4.1.1 |
   |---|---|---|
   | `@DataJpaTest` | `...boot.test.autoconfigure.orm.jpa` | `...boot.data.jpa.test.autoconfigure` |
   | `TestEntityManager` | `...boot.test.autoconfigure.orm.jpa` | `...boot.jpa.test.autoconfigure` |
   | `@AutoConfigureTestDatabase` | `...boot.test.autoconfigure.jdbc` | `...boot.jdbc.test.autoconfigure` |

   三个注解分居三个包，IDE 的自动补全帮不上忙，只能查制品。
   这一篇和前一篇一样，测试注解的包名都变了——**每换一个技术栈都得重新确认一遍**。

4. **`spring.jpa.open-in-view` 默认还是 `true`。** Boot 4 没有改掉它，
   默认值仍是开启。本工程显式关掉，是为了让「不小心在视图层碰懒加载」的问题
   在开发阶段就暴露成 `LazyInitializationException`，而不是上线后变成慢查询。
   关掉之后有一条硬规矩：**实体转 DTO 必须写在 `@Transactional` 方法内部**，
   包括所有读懒加载集合的代码。

5. **`spring.jpa.defer-datasource-initialization` 还在，但配置元数据里查不到。**
   它已经不是 `JpaProperties` 的字段，改成由 `JpaDependsOnDatabaseInitializationDetector`
   直接从 `Environment` 里读。所以 IDE 不补全、元数据里搜不到，
   写进 yml 却照样起作用。按「字段没了就是删了」去推断会误判。

6. **Boot 4 新增了 `spring.jpa.bootstrap`**，取值 `DEFAULT` / `ASYNC`，
   用来控制 `EntityManagerFactory` 的初始化方式。选 `ASYNC` 需要自己提供
   `BootstrapExecutor`，否则抛 `BootstrapExecutorRequiredException`。

7. **`spring.jpa.hibernate.use-new-id-generator-mappings` 已废弃**，
   标了 `level = error`，写在配置里会看到告警。

8. **枚举默认还是存序号。** JPA 规范里 `@Enumerated` 的默认值是 `ORDINAL`，
   不是 `STRING`。不显式写 `@Enumerated(EnumType.STRING)`，
   枚举值就会以 `0`、`1`、`2` 存进库——往枚举中间插一个值，历史数据的语义全乱。

9. **`Sort` 收的是实体属性名，`OrderItem` 收的是列名。** 见第五节末尾。
   从 MyBatis-Plus 迁到 Spring Data 时，这一处如果只改类型不改值，
   列表页会直接抛异常。切片测试里有一条用例专门盯着它。

10. **要注意时间字段精度。** 见第六节「创建订单」。
    `LocalDateTime.now()` 带纳秒，`DATETIME(3)` 只存到毫秒，
    不在写入前截断，同一个字段就会有两个值。这一条第 2 篇也踩过，是同一类问题。

11. **乐观锁跨请求不生效。** `@Version` 只保护同一个事务内的并发，
    客户端手里那个版本号必须自己在服务层比。见第六节「乐观锁」。

## 十、许可

MIT License，作者 Xiong Neng。
