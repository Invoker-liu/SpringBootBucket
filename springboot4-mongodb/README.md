# springboot4-mongodb

Spring Boot 4 全家桶教程 · 第 4 篇配套工程：**换成 MongoDB，把订单接口再写一遍**。

接口路径、状态码、请求与响应结构跟前三篇保持一致，换掉的只有持久层。
前三篇的订单主键是自增数字，这一篇是 ObjectId 字符串——这是接口层面唯一看得见的差别。
真正不一样的东西都在底下：没有表、没有 join、没有跨文档事务，一整套建模思路都要换。

## 一、这个工程演示了什么

| 主题 | 具体做法 |
|---|---|
| 文档映射 | `@Document(collection = "orders")` + `@Field` 声明存储名，字段用下划线风格和前三篇对齐 |
| 内嵌数组 | 明细不建独立集合，直接作为 `List<OrderItem>` 嵌在订单文档里 |
| 主键 | 声明成 `String`，由驱动生成 24 位 ObjectId，不写转换器 |
| 索引 | `@Indexed` / `@CompoundIndex` 声明索引，**并且知道默认不会自动创建** |
| 唯一约束 | `@Indexed(unique = true)` + 驱动错误码 11000，落到 `DuplicateKeyException` |
| 审计字段 | `@EnableMongoAuditing` + `@CreatedDate` / `@LastModifiedDate`，时间戳由框架回填 |
| 计算字段 | Spring Data 的 `@Transient`，让 `amount` 只出现在响应里、不落库 |
| 派生查询 | `findByOrderNo` / `countByStatus`，方法名即查询 |
| `@Query` | 手写 JSON 条件，含点号路径钻进内嵌数组按商品反查 |
| `@Aggregation` | 管道写死在注解里，`$group` 做状态统计 |
| fluent 聚合 | `MongoTemplate` 手搭管道，含 `$unwind` 摊平明细后按商品汇总 |
| 模板层更新 | `findAndModify` / `$push` / `$pull`，把并发安全压在单文档原子操作上 |
| 乐观锁 | `@Version` + `save()`，以及它和 JPA 侧**不是同一个异常**这件事 |
| 分页 | `Pageable` + 统一的分页响应体，不把 `Page` 直接当契约 |
| 投影 | 接口投影（可移植）与手写 `fields`（不可移植的反例）并排对照 |
| 测试 | `@DataMongoTest` 切片测试 + `@SpringBootTest` 集成测试，都真连 MongoDB |

## 二、环境要求

| 组件 | 版本 |
|---|---|
| JDK | 21 |
| Maven | 3.9+ |
| Spring Boot | 4.1.1（Spring Framework 7.0.9） |
| Spring Data MongoDB | 5.1.1 |
| MongoDB Java Driver | 5.8.1（同步版 `mongodb-driver-sync`） |
| MongoDB Server | 8.0+（本工程在 8.0.32 上验证） |

驱动版本值得留意：Boot 4.1.1 的管理依赖锁的是 **5.8.1**，
而 Spring Data MongoDB 5.x 要求驱动 **5.6 以上**，两者是一致的。
版本号别抄二手资料，`mvn dependency:tree` 跑一遍最快。

需要一台可连的 MongoDB。**本工程用单节点，不涉及副本集，因此没有多文档事务** ——
这一点会直接影响 Service 层的写法，见第八节。

## 三、准备数据库

MongoDB 是 schema-less 的，**没有建表脚本**。库和集合都会在第一次写入时自动出现。

```bash
# 建库和账号（如果还没有的话），在 mongosh 里执行
use admin
db.createUser({ user: "root", pwd: "root123456", roles: ["root"] })
```

连接信息通过环境变量覆盖，不写死在代码里：

| 变量 | 默认值 |
|---|---|
| `MONGO_HOST` | `127.0.0.1` |
| `MONGO_PORT` | `27017` |
| `MONGO_DB` | `springboot4_mongo` |
| `MONGO_USER` | `root` |
| `MONGO_PASSWORD` | `root123456` |

库名带 `springboot4_` 前缀，不会和别的工程撞。测试用的是另外一个库
`springboot4_mongo_test`，索引反例测试用 `springboot4_mongo_indexdemo`，
跑测试不会把演示数据洗掉。

### 一个必须显式打开的开关

```yaml
spring:
  data:
    mongodb:
      auto-index-creation: true
```

**这个开关的默认值是 `false`。** 也就是说，不写这一行，
实体上那一堆 `@Indexed(unique = true)`、`@CompoundIndex` 全部只是注释：

- 应用正常启动
- 文档正常写入
- 派生查询正常返回
- 唯一约束**不存在**，同一个订单号能插进去两条
- 全程没有任何报错、任何警告

本工程打开它，然后在第六节用一次真实的重复插入把这个差别打出来。

## 四、运行

```bash
cd springboot4-mongodb

# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package
java -jar target/springboot4-mongodb-1.0.0.jar
```

启动日志里看到这一行即成功：

```
Tomcat started on port 8080 (http) with context path '/'
Started MongoApplication in 2.86 seconds
```

## 五、接口清单

| 方法 | 路径 | 说明 | 成功状态码 |
|---|---|---|---|
| POST | `/api/orders` | 创建订单，订单号由服务端生成 | 201 + `Location` |
| GET | `/api/orders/{id}` | 按主键查询，带明细 | 200 |
| GET | `/api/orders` | 分页 + 条件查询 | 200 |
| GET | `/api/orders/stats` | 按状态聚合统计（`@Aggregation`） | 200 |
| GET | `/api/orders/stats/by-template` | 同一个统计，`MongoTemplate` 手搭管道 | 200 |
| GET | `/api/orders/stats/naive` | **反例**：别名写错，金额整列丢失 | 200 |
| GET | `/api/orders/top-products` | 商品销量排行（`$unwind` + 二次分组） | 200 |
| GET | `/api/orders/summary` | 订单摘要（接口投影，只查四个字段） | 200 |
| GET | `/api/orders/summary/projection` | **反例**：手写 `fields` 投影，未查字段全为 null | 200 |
| GET | `/api/orders/count` | 某状态下的文档数 | 200 |
| GET | `/api/orders/statuses` | 出现过的所有状态（服务端 distinct） | 200 |
| GET | `/api/orders/expensive` | 金额不低于指定值的订单 | 200 |
| GET | `/api/orders/by-item` | 按内嵌明细里的商品名反查订单 | 200 |
| PUT | `/api/orders/{id}` | 整体替换业务字段 | 200 |
| PATCH | `/api/orders/{id}/status` | 状态流转 | 200 |
| POST | `/api/orders/{id}/items` | 追加一条明细（`$push`） | 200 |
| DELETE | `/api/orders/{id}/items` | 移除指定商品的明细（`$pull`） | 200 |
| DELETE | `/api/orders/{id}` | **真删**，不是软删除 | 204 |

### 查询参数

| 参数 | 默认值 | 约束 |
|---|---|---|
| `keyword` | 无 | 模糊匹配订单号或客户姓名 |
| `status` | 无 | `CREATED` / `PAID` / `SHIPPED` / `COMPLETED` / `CANCELLED` |
| `page` | `0` | ≥ 0 |
| `size` | `10` | 1 ~ 100 |
| `sort` | `createdAt,desc` | 字段取自白名单，方向为 `asc`/`desc` |

**`sort` 传的是实体属性名，不是文档里的存储名。** 白名单在 `OrderPageQuery` 里，
只认 `orderNo`、`customerName`、`totalAmount`、`status`、`createdAt`、`updatedAt`。

有意思的是，传 `created_at` 也能跑出正确结果——因为 MongoDB 允许按任意文档路径排序，
白名单恰好放行了这个字符串，`Sort` 下发过去正好对上了存储名。
但接口只承诺一种口径，别拿这个巧合当特性。

### 状态机

```
CREATED ──> PAID ──> SHIPPED ──> COMPLETED
   │          │
   └──────────┴──────> CANCELLED
```

和前三篇是同一个枚举、同一套规则。区别只在实现：
JPA 那边靠事务 + 脏检查，这里靠 `findAndModify` 把「读到的状态」变成「更新的条件」，
在服务端一次完成判断和写入。见第六节「状态流转」。

## 六、手工测试

以下响应都是在本机真实跑出来的，可以直接对照。应用用的是默认端口 `8080`。

### 创建订单

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerName": "熊大",
    "customerPhone": "13800138000",
    "totalAmount": 299.50,
    "remark": "演示下单",
    "items": [
      { "productName": "机械键盘", "price": 299.50, "quantity": 1 },
      { "productName": "腕托", "price": 25.00, "quantity": 2 }
    ]
  }'
```

```
HTTP/1.1 201
Location: http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597
Content-Type: application/json

{"id":"6aad2e3f603b044e25ffe597","orderNo":"ORD202609182027433760839",
 "customerName":"熊大","customerPhone":"138****8000","totalAmount":299.50,
 "status":"CREATED","statusLabel":"已创建","remark":"演示下单",
 "items":[{"productName":"机械键盘","price":299.50,"quantity":1,"amount":299.50},
          {"productName":"腕托","price":25.00,"quantity":2,"amount":50.00}],
 "version":0,
 "createdAt":"2026-09-18T20:27:43.379",
 "updatedAt":"2026-09-18T20:27:43.379"}
```

两处和前三篇不一样的地方：

`Location` 里那串 `6aad2e3f603b044e25ffe597` 是 24 位十六进制的 ObjectId，
不是自增数字。接口本身没变，但所有假设「主键是递增整数」的客户端代码都得改
——比如「按 id 倒序就是最新的」这条，在 ObjectId 上不成立。

`amount` 这个字段是算出来的，`price × quantity`。它**不在数据库里**，
只存在于响应里。靠的是 `OrderItem.amount` 上的 Spring Data `@Transient`。
忘了写这个注解，它会变成一个落库的真实字段，而且永远是第一次写入时的值，
再也不会更新——一个不报错的幽灵字段。

### 乐观锁

```bash
# 带上正确版本号更新，200，版本号变成 1
curl -i -X PUT http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":888.00,
       "remark":"改了金额","version":0}'

# 再拿已经过期的版本号 0 更新，409
curl -i -X PUT http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":666.00,
       "remark":"并发写入","version":0}'
```

```
HTTP/1.1 409
Content-Type: application/problem+json

{"detail":"订单已被其他人修改，请刷新后重试（当前版本 1，提交版本 0）",
 "instance":"/api/orders/6aad2e3f603b044e25ffe597","status":409,
 "title":"业务规则不满足","type":"urn:problem-type:business-rule-violation"}
```

这里抛的异常是 `org.springframework.dao.OptimisticLockingFailureException`，
**不是** JPA 那边的 `ObjectOptimisticLockingFailureException`。

两者是父子关系（后者继承前者），差别很实在：
父类没有 `getPersistentClassName()` 方法。把第 3 篇的异常处理器原样复制过来，
**编译就过不去**。这个错误比读文档快多了。

### 状态流转

```bash
# 合法：CREATED -> PAID，200
curl -i -X PATCH http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597/status \
  -H "Content-Type: application/json" -d '{"status":"PAID"}'

# 非法：PAID -> CREATED，422
curl -i -X PATCH http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597/status \
  -H "Content-Type: application/json" -d '{"status":"CREATED"}'
```

```
HTTP/1.1 422
Content-Type: application/problem+json

{"detail":"订单不允许从 已支付 变更为 已创建",
 "instance":"/api/orders/6aad2e3f603b044e25ffe597/status","status":422,
 "title":"业务规则不满足","type":"urn:problem-type:business-rule-violation"}
```

这一条的实现值得单独说。JPA 那边是「在事务里读出实体、判断、改、提交」，
靠事务隔离把并发挡住。MongoDB 单机没有事务可用，所以换成：

```java
// 把"读到的状态"变成"更新的条件"，一次 findAndModify 完成判断与写入
mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(id).and("status").is(expected)),
        new Update().set("status", target).inc("version", 1),
        FindAndModifyOptions.options().returnNew(true),
        Order.class);
```

条件里带上 `status`，意味着如果别人在这中间改了状态，这次更新匹配不到任何文档，
返回 null，服务层据此抛 422。**判断和写入之间没有任何窗口**，
因为它们在服务端是同一条命令。

### 明细的原子增删

```bash
# 追加一条明细，服务端 $push
curl -i -X POST http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597/items \
  -H "Content-Type: application/json" \
  -d '{"productName":"鼠标垫","price":18.00,"quantity":3}'

# 移除指定商品的明细，服务端 $pull
curl -i -X DELETE "http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597/items?productName=鼠标垫"
```

```
HTTP/1.1 200
Content-Type: application/json

{"removed":1}
```

这两条如果按前三篇的思路写，会是「读出订单、改 items 列表、整体 save 回去」。
文档型存储有更好的办法：`$push` / `$pull` 直接在服务端改数组元素，
不需要把整份文档传回来再写回去。

差别不只是"少传点数据"。整份 save 回去意味着**用旧快照覆盖新状态**——
两个人同时加明细，后提交的那个会把先提交的那条吃掉。`$push` 没有这个问题。

`$pull` 的语义要留意：它按条件移除**所有**匹配的元素。同名商品有两条时会被一起删掉，
返回值 `removed: 1` 指的是「影响了一条文档」，不是「删了一个元素」。

### 聚合统计（三种写法对照）

```bash
curl -s http://localhost:8080/api/orders/stats             # @Aggregation 注解版
curl -s http://localhost:8080/api/orders/stats/by-template # MongoTemplate 代码版
curl -s http://localhost:8080/api/orders/stats/naive       # 反例
```

```
注解版   ：[{"count":1,"status":"CANCELLED","statusLabel":"已取消","totalAmount":86.00},
           {"count":2,"status":"CREATED","statusLabel":"已创建","totalAmount":1198.50},
           {"count":2,"status":"PAID","statusLabel":"已支付","totalAmount":2468.00}]
代码版   ：（同上，逐字一致）
反例代码版：[{"count":1,"status":"CANCELLED","statusLabel":"已取消"},
           {"count":2,"status":"CREATED","statusLabel":"已创建"},
           {"count":2,"status":"PAID","statusLabel":"已支付"}]
```

前两个必须逐字一致，这是切片测试里钉住的一条断言。第三个是故意的反例，
**`totalAmount` 整列消失了**，而且没有任何报错。

这是本篇最隐蔽的一个坑，值得花点篇幅讲清楚。

返回类型 `OrderStatusStat` 里，金额字段上标的是 `@Field("total_amount")`：

```java
public class OrderStatusStat {
    @Field("_id")
    private OrderStatus status;
    private long count;
    @Field("total_amount")
    private BigDecimal totalAmount;
}
```

两条实现路径对「输出别名」的处理规则**不一样**。打开驱动命令日志就能看到：

```
注解版（注解里写的是 totalAmount）
  {"$group": {"_id": "$status", "count": {"$sum": 1},
              "total_amount": {"$sum": "$total_amount"}}}

代码版（.as("total_amount")）
  {"$group": {"_id": "$status", "count": {"$sum": 1},
              "total_amount": {"$sum": "$total_amount"}}}

反例代码版（.as("totalAmount")）
  {"$group": {"_id": "$status", "count": {"$sum": 1},
              "totalAmount": {"$sum": "$total_amount"}}}
```

第一条和第三条的**源码里写的都是 `totalAmount`**，下发出去却不一样：
注解版被翻译成了 `total_amount`，代码版原样是 `totalAmount`。

- `@Aggregation` 声明的管道，会走一遍实体映射，**连输出别名也当属性名翻译**；
- fluent API（`Aggregation.group().sum().as()`）不翻译，写什么发什么。

所以结论有点反直觉：**注解版里可以写属性名，代码版里必须写存储名。**
两套规则不一样，写错的表现是「字段静默消失」。

想自己复现这组报文，把 `org.mongodb.driver.protocol.command` 的日志级别调成 `DEBUG`
（`application.yml` 里已经写好注释，取消注释即可）。别忘了关掉，输出量很大。

**注意别用中间层日志取证。** `org.springframework.data.mongodb.core.MongoTemplate`
那层打的是翻译前的 `Query`：`findById` 在日志里是 `{ "id" : "..." }`，
而实际发出去的是 `filter: {"_id": {"$oid": "..."}}`。照着中间层日志去库里查，
一条都对不上。只有驱动层的 `Command: {...}` 是权威报文。

### 商品销量排行

```bash
curl -s "http://localhost:8080/api/orders/top-products?limit=3"
```

```
[{"productName":"腕托","totalQuantity":4,"salesAmount":100.00},
 {"productName":"机械键盘","totalQuantity":3,"salesAmount":1586.50},
 {"productName":"鼠标垫","totalQuantity":2,"salesAmount":36.00}]
```

这是文档型存储最不直观的一步。明细是订单里的一个数组字段，
想按「商品」这个粒度统计，得先用 `$unwind` 把数组拆开——
一条含 2 个商品的订单会变成 2 条记录，每条带一个商品——然后再分组。

等价于关系型的 `join t_order_item`，但不需要提前把明细建模成独立实体。
代价是 `$unwind` 之后中间结果会变大，订单里明细越多越明显，
所以这一步之后通常紧跟着 `$group` 把行数收回来。

### 接口投影与它的反例

```bash
curl -s "http://localhost:8080/api/orders/summary?status=CANCELLED"
curl -s "http://localhost:8080/api/orders/summary/projection?status=CANCELLED"
```

```
接口投影：[{"customerName":"熊三","id":"6aad2e3f603b044e25ffe595",
           "orderNo":"ORD202609182027432781007","status":"CANCELLED"}]

手写 fields 反例：[{"id":"6aad2e3f603b044e25ffe595","orderNo":"ORD202609182027432781007",
           "customerName":"熊三","totalAmount":86.00,"status":"CANCELLED",
           "statusLabel":"已取消","items":[]}]
```

接口投影（`OrderSummary` 接口 + getter）只查声明了的字段，而且**换存储也能用**——
JPA 那边是同一套写法（第 3 篇演示过）。

反例这一版用手写 `fields` 投影，只查了四列，却返回 `OrderResponse`。
结果是 `remark`、`version`、`createdAt`、`updatedAt` 全是 null，
`items` 是一个空列表。字段一个不少地出现了，但里面没有内容。

**一个看起来正常、其实是空壳的字段，比直接缺字段更容易骗过联调。**
前一版的分页接口如果误用了这个查询，前端会拿到一堆「客户名对、金额对、备注空」的数据，
排查半天才发现是投影写窄了。

### 参数校验与错误响应

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"","customerPhone":"123","totalAmount":-1}'
```

```
HTTP/1.1 400
Content-Type: application/problem+json

{"detail":"请求体字段校验未通过，逐字段原因见 errors",
 "instance":"/api/orders","status":400,"title":"请求体校验失败",
 "errors":[{"message":"订单金额必须大于 0","field":"totalAmount"},
           {"message":"客户姓名不能为空","field":"customerName"},
           {"message":"客户手机号格式不正确","field":"customerPhone"}]}
```

嵌套校验也是有效的，明细列表上标了 `@Valid` 之后，错误路径形如 `items[0].price`。

上面那段响应体，`errors` 里每个条目的键顺序、以及数组本身的顺序，**在不同 JVM 启动之间不保证一致**：
条目是 `Map.of` 构造的，而校验框架返回的违规集合本身无序。
写断言时按 `field` 找，不要按下标取，否则会得到一条只在某些启动里红的测试。

顺带一个和前三篇一样的规矩：**Controller 类上不要写 `@Validated`**。
写了之后方法参数校验改走 AOP 代理，抛的是 `ConstraintViolationException`，
全局异常处理器接不到，400 会变成 500。

### 不合法的主键

```bash
curl -i http://localhost:8080/api/orders/not-an-objectid
```

```
HTTP/1.1 404
Content-Type: application/problem+json

{"detail":"订单不存在：not-an-objectid","instance":"/api/orders/not-an-objectid",
 "status":404,"title":"资源不存在","type":"urn:problem-type:resource-not-found",
 "resourceType":"订单","resourceId":"not-an-objectid"}
```

服务层先用 `ObjectId.isValid` 挡了一道。不挡的话，驱动收到一个转不了的字符串会抛
`IllegalArgumentException`，结果是 500——一个拼错的 URL 换来一条服务器错误日志，
查起来很浪费时间。

### 真删不是软删除

```bash
curl -i -X DELETE http://localhost:8080/api/orders/6aad2e3f603b044e25ffe596
```

```
HTTP/1.1 204
```

第 3 篇用 `@SoftDelete` 实现软删除，`deleteById` 生成的是一条 `UPDATE ... SET deleted=true`。
MongoDB 没有对应的注解，`deleteById` 就是一条 delete 命令，文档真的消失了。
删完再查是 404，不是「被标记位过滤掉」。

要做软删除只能自己加一个字段再在查询里带上它。
代价是所有查询都得记得加这个条件，漏一个就是一个 bug——这也是 JPA 那边
用 `@SoftDelete` 更省事的原因。

### 唯一索引真的在拦

应用层造不出重复订单号（订单号是服务端生成的），所以这一步直接绕过应用写一次库：

```
被拦下：DuplicateKeyError: E11000 duplicate key error collection:
springboot4_mongo.orders index: uk_order_no dup key:
{ order_no: "ORD202609182027433760839" }
```

错误码 **11000**，对应 Spring 的 `org.springframework.dao.DuplicateKeyException`，
本工程的全局异常处理器把它映射成 409。

JPA 那边同样的场景抛的是 `DataIntegrityViolationException`，
而 `DuplicateKeyException` 是它的子类。父子方向和第 3 篇相反，
处理器里两个都接上最省心。

### 查库对账

```bash
# 索引
db.orders.getIndexes()
_id_                      {_id: 1}
idx_status_created_at     {status: 1, created_at: -1}
uk_order_no               {order_no: 1}  [unique]
idx_status                {status: 1}
```

文档的原始形态（字段顺序照 BSON 原样）：

```json
{"_id": {"$oid": "6aad2e3f603b044e25ffe593"},
 "order_no": "ORD202609182027431545824",
 "customer_name": "熊大",
 "customer_phone": "13800138000",
 "total_amount": {"$numberDecimal": "299.50"},
 "status": "CREATED",
 "remark": "单件键盘",
 "version": 0,
 "created_at": {"$date": "2026-09-18T12:27:43.202Z"},
 "updated_at": {"$date": "2026-09-18T12:27:43.202Z"},
 "items": [{"product_name": "机械键盘",
            "price": {"$numberDecimal": "299.50"},
            "quantity": 1}],
 "_class": "com.xncoding.mongo.domain.Order"}
```

三处值得看的地方：

**字段名全是蛇形，接口返回的却是驼峰。** `order_no` / `customer_name` / `total_amount` /
`created_at` 这些存储名是 `@Field` 指定的，`@Field` 只影响存储，
序列化给客户端时用的还是 Java 属性名。一层映射，两边都干净。

**没有 `amount` 字段。** 这正是那个 `@Transient` 的效果。上面接口返回里有 `amount`，
库里没有——计算字段就该是这样。

**金额是 `Decimal128` 而不是 `Double`。** `BigDecimal` 在 5.x 里默认映射到
`Decimal128`（旧版默认是 `STRING`）。这是 5.x 的破坏性变更之一，
换成 `Double` 会出现 `299.50` 变成 `299.5` 这种情况，看起来一样但比较起来不等。

还有 `_class` 那一项，是 Spring Data 用来支持多态映射的，属正常现象。

## 七、运行测试

```bash
mvn test
```

预期结果：

```
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 技术 | 覆盖内容 |
|---|---|---|
| `OrderRepositorySliceTest` | `@DataMongoTest` + 真实 MongoDB | 16 个用例：索引自动创建、唯一索引拦截、字段名映射、`@Query` 字段名翻译、`Update` 翻译、内嵌数组、`$push`/`$pull`、聚合两种写法对照、分页、两种投影、distinct |
| `OrderApiIntegrationTest` | `@SpringBootTest` + `MockMvcTester` + 真实 MongoDB | 36 个用例：CRUD、分页与排序、乐观锁、状态机、原子增删、真删、参数校验、problem 文档结构 |
| `MongoPropertyRenameTest` | `ApplicationContextRunner` | 6 个用例：旧属性静默失效、新属性正常绑定、uri 优先级 |
| `GlobalExceptionHandlerTest` | 直连 handler | 4 个用例：`DuplicateKeyException` → 409、`OptimisticLockingFailureException` → 409 |
| `MongoIndexCreationOffTest` | `@DataMongoTest` + 关掉索引开关 | 1 个用例：反面对照，索引一个都不建、唯一约束不存在 |

关于测试有四点说明：

1. **测试直连真实 MongoDB，不换成嵌入式版本。** 本篇要验证的索引创建、唯一约束、
   驱动错误码、`Decimal128` 精度，全是服务端行为，换成内存实现就成了「测了个仿制品」。
2. **库连不上时整体跳过，而不是报错。** 用例执行前先探测一次 `ping`，
   连不上就直接 skip。在一台没配 MongoDB 的机器上跑 `mvn test` 会看到 `Skipped`，不会看到一片红。
3. **没有 `@Transactional`，也没有 `@Rollback`。** 单节点 MongoDB 不支持多文档事务，
   Spring 的事务管理器根本不会介入。所有测试的清理都是在 `@BeforeEach` 里手动清集合。
   这是本篇和第 3 篇在测试写法上最大的差别。
4. **`@DataMongoTest` 不扫自定义 `@Repository`。** 它收的是「Spring Data 仓储」，
   即由框架生成代理的接口。自己写的 `OrderMongoDao` 必须显式 `@Import` 进来，
   否则注入直接报 `NoSuchBeanDefinitionException`。

## 八、工程结构

```
src/main/java/com/xncoding/mongo/
├── MongoApplication.java            启动类，@EnableMongoAuditing + 自定义 DateTimeProvider
├── controller/
│   └── OrderController.java         资源入口，含 sort 表达式解析与两个反例接口
├── service/
│   └── OrderService.java            业务规则 + 单文档原子操作，没有任何 @Transactional
├── repository/
│   ├── OrderRepository.java         派生查询 / @Query / @Aggregation
│   ├── OrderMongoDao.java           MongoTemplate：findAndModify / $push / $pull / 聚合
│   ├── OrderSummaryRepository.java  只读仓储，只暴露一个方法
│   └── OrderSummary.java            接口投影
├── domain/
│   ├── Order.java                   聚合根，@Document + 索引 + 审计字段 + @Version
│   ├── OrderItem.java               内嵌明细，含 @Transient 计算字段
│   ├── OrderStatus.java             状态枚举 + 状态机
│   └── OrderPageQuery.java          查询条件，含排序白名单
├── dto/                             请求体与响应体，与前三篇保持一致
└── exception/                       404 / 409 / 422 / 400 统一输出

src/main/resources/
└── application.yml                  连接、审计、索引开关、problem details
```

**`OrderService` 里一个 `@Transactional` 都没有。** 这不是漏了，是本篇的核心结论之一：
单节点 MongoDB 不支持多文档事务，写了也不会生效，反而会给人一种「有事务保护」的错觉。

那并发安全靠什么？全部压在**单文档原子操作**上：

| 场景 | 做法 | 保证 |
|---|---|---|
| 整体更新 | `save()` + `@Version` | 乐观锁，版本冲突抛 409 |
| 状态流转 | `findAndModify` + 当前状态作条件 | 判断与写入在服务端原子完成 |
| 追加明细 | `$push` | 服务端改数组，不覆盖整份文档 |
| 移除明细 | `$pull` | 同上 |

这套做法的前提是**业务操作都能收进一个文档里**。这也是文档型建模的取舍所在：
把明细嵌进订单，换来了单文档原子性，代价是「按商品维度统计」要多写一个 `$unwind`、
而且单个文档不能无限大（BSON 上限 16MB）。

真要跨文档事务，得上副本集并且显式配置 `MongoTransactionManager`。
本工程单节点，不涉及。

## 九、从 Spring Boot 3 迁过来要注意什么

1. **连接属性整体改名，而且旧名是静默失效。**

   | Boot 3 | Boot 4 |
   |---|---|
   | `spring.data.mongodb.uri` | `spring.mongodb.uri` |
   | `spring.data.mongodb.host` / `port` / `database` | `spring.mongodb.*` |
   | `spring.data.mongodb.username` / `password` | `spring.mongodb.*` |
   | `spring.data.mongodb.authentication-database` | `spring.mongodb.authentication-database` |
   | `management.health.mongo.enabled` | `management.health.mongodb.enabled` |
   | `management.metrics.mongo.*` | `management.metrics.mongodb.*` |

   大约 13 项连接层属性全部改名，元数据里标了 `level = error`、`since = 4.0.0`。

   **但「静默失效」这四个字比「改名」重要得多。** 旧名不是「还能用但会告警」，
   而是彻底不绑定：写了等于没写，客户端会悄悄退回默认的 `mongodb://localhost/test`，
   日志里一句提示都没有。表现出来是「连不上库」或者「连上了但数据不见了」，
   而配置项看上去明明是对的。

   本工程的 `MongoPropertyRenameTest` 专门把这件事钉住了：只配旧属性，
   断言最终拿到的连接串仍然是默认值。

   > **取证方法有个坑，记一下。** 别用 `MongoProperties.determineUri()` 去判断
   > 「配置生效了没有」。这个方法只有三行，**只认 `uri` 一个字段**：
   > `return uri != null ? uri : "mongodb://localhost/test";`
   > 分字段配置（host/port/username）时它一概不看，永远返回默认值。
   > 权威来源是 `MongoConnectionDetails#getConnectionString()`，
   > 那才是 `MongoClient` 真正拿到的连接串。

2. **`spring.data.mongodb.auto-index-creation` 等一批属性没有跟着改名。**

   同一个 yml 里会出现这种写法：

   ```yaml
   spring:
     mongodb:
       uri: mongodb://...        # 连接层，新名字
     data:
       mongodb:
         auto-index-creation: true   # 数据层，还是老名字
   ```

   原因是 Boot 4 把 MongoDB 自动配置拆成了两个模块：
   `spring-boot-mongodb` 管连接（对应 `MongoProperties`，即 `spring.mongodb.*`），
   `spring-boot-data-mongodb` 管 Spring Data 层（对应 `DataMongoProperties`，
   即 `spring.data.mongodb.*`）。两套属性各自归类，改名只发生在连接层。

   所以**迁移时不能一把全局替换 `spring.data.mongodb.` → `spring.mongodb.`**。
   除了 `auto-index-creation`，还留在老名字下的有 `field-naming-strategy`、
   `repositories.type`、`representation.big-decimal`、`gridfs.*`。改错了同样是静默失效。

3. **`@Indexed` 默认不会创建索引。** `auto-index-creation` 的默认值是 `false`，
   这一点十篇教程有九篇不提。忘了打开开关，代码里写着 `@Indexed(unique = true)`，
   库里什么都没有，而且不报错。见第三节与第六节。

4. **聚合输出的别名，两条实现路径规则不同。** `@Aggregation` 会按实体映射翻译输出别名，
   fluent API 不会。详见第六节「聚合统计」。这一条是从 Spring Boot 3 迁过来也不会变的
   Spring Data 行为，但很容易被忽略，因为错了不报错。

5. **手写的 `@Query`、`Criteria`、`Update` 都会翻译字段名。**
   这和我最初的假设相反——我以为「手写 JSON 就是原样下发」。
   实测是：`Criteria.where("items.productName")` 会被翻成 `items.product_name`，
   `Update.set("customerName", ...)` 会落到 `customer_name` 上，而且不会多出同义字段。
   `@Query` 更是两种写法结果一致。切片测试里三个方向的用例都在。

6. **测试注解又一次换了包名。**

   | 注解 | Boot 3 | Boot 4.1.1 |
   |---|---|---|
   | `@DataMongoTest` | `...boot.test.autoconfigure.data.mongo` | `...boot.data.mongodb.test.autoconfigure` |
   | `@AutoConfigureDataMongo` | 同上 | 同上 |

   规律和第 1、3 篇一致：**包名 = 模块路径 + `.test.autoconfigure`**。
   01 篇是 `webmvc.test.autoconfigure`，03 篇是 `data.jpa.test.autoconfigure`，
   这一篇是 `data.mongodb.test.autoconfigure`。

7. **乐观锁与唯一键的异常类型和 JPA 侧不同，照抄编译不过。**

   | 场景 | JPA（第 3 篇） | MongoDB（本篇） |
   |---|---|---|
   | 乐观锁冲突 | `ObjectOptimisticLockingFailureException` | `OptimisticLockingFailureException`（父类） |
   | 唯一键冲突 | `DataIntegrityViolationException` | `DuplicateKeyException`（子类，错误码 11000） |

   乐观锁这一组是**子类 → 父类**，唯一键这一组是**父类 → 子类**，方向相反。
   把上一章的处理器复制过来，`getPersistentClassName()` 那一行会直接编译失败。

8. **没有 `@SoftDelete`，删除是真删。** 需要软删除只能自己加字段并保证每个查询都带上它。

9. **`BigDecimal` 的默认表示方式变了。** Spring Data MongoDB 5.x 里，
   `BigDecimal` / `BigInteger` 的默认转换从 `STRING` 改成了 `DECIMAL128`，
   而且取消了默认值——不显式配置会有告警。UUID 的表示方式同样取消了默认值。
   这两条都在官方 4.x → 5.x 迁移指南里。

10. **`@Transient` 要用对包。** 必须用 `org.springframework.data.annotation.Transient`，
    不是 `jakarta.persistence.Transient`。用错的话注解不生效，
    计算字段会变成落库的真实字段。这个错误 IDE 不会提示，因为两个注解都合法。

11. **`@Meta.allowDiskUse` 的类型变了。** 由 `boolean` 改成 `String`
    （5.x 破坏性变更）。管大聚合的内存限制，同一条变更里还有 JMX 支持被移除。

12. **`Date` 精度。** `LocalDateTime.now()` 带纳秒，BSON 日期是毫秒整数。
    不截断的话，响应里的时间戳和落库值会差一截——内存里的对象还留着纳秒。
    本工程用自定义 `DateTimeProvider` 把时间截到毫秒。这一条第 2、3 篇也遇到过，
    只是原因不同（那边是 MySQL 的 `DATETIME(3)` 丢掉纳秒，这边是驱动丢掉纳秒）。

## 十、许可

MIT License，作者 Xiong Neng。
