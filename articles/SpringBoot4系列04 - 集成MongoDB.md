---
title: SpringBoot4系列04 - 集成MongoDB
slug: sb4-mongodb
date: 2026-09-20 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, MongoDB, NoSQL ]
draft: false
---

上一篇结尾我留过一个判断：`OrderRepository` 这个接口再换一次实现，如果还那么顺，分层这件事就算做对了。

这次换 MongoDB。

先把我的结论放这儿：接口契约又是一个字没改，Controller、DTO、状态机、错误响应的形状全部原样，连 `/api/orders/{id}` 路径都一样。唯一看得见的变化是主键从自增数字变成了 24 位十六进制的 ObjectId。

但从这一篇开始，「换个实现」这件事的性质变了。前两篇换的是同一个世界里的不同工具，第二篇是 MyBatis-Plus，第三篇是 JPA，底下都是表、行、列、事务。这次底下那套东西全都不在。

我踩到的最大的坑跟 MongoDB 本身没关系，出在连接属性的前缀上：我按错误的前缀写完配置，应用照样起来，接口全部正常，过了很久才发现连的并不是配置里那个库。

也没有报错，也没有警告，就是安安静静地连到了 `localhost` 的默认库。

![](https://static.xiongneng.me/config-silent-fallback-20260921220212.png)

这事我后面单独拿一节说。它比第三篇那个「SQL 日志一行都没有」还难受：日志没有至少还能察觉，这个是全绿。

代码全部可跑，工程在 `springboot4-mongodb` 目录下。这一篇的 MongoDB 跑在我本机 Windows 上，不在树莓派上，原因我放在最后交代。

## 先分清文档型和关系型

表还是表、行还是行的日子在前两篇。这个概念从这一篇开始必须讲清楚，不然后面所有设计都看着别扭。

![](https://static.xiongneng.me/relational-vs-document-20260921220212.png)

关系型这边，订单要有明细就得建两张表，用外键连起来。查一条完整订单要 join，写一条要写两张表，而且必须在事务里，不然中间失败会留下半条订单。

MongoDB 存的是文档，一串 BSON 对象，字段可以嵌套，明细直接嵌在订单里面，是个数组字段。

一条订单就是一条文档，一个集合就是一堆文档。没有 join，因为它压根不需要。

这里有个词要换掉：没有表，叫集合；没有行，叫文档；没有列，叫字段。平时混着叫没关系，你看官方文档时得知道它说的是什么。

还有就是没有 schema：MongoDB 不建表，我第一次往 `orders` 里写文档的时候，集合自己就出现了，库也不需要 `create database`。关系型要先建表、字段类型定死，改起来得写 DDL。

听着很自由，代价是约束没了：写进去 `"299.50"` 和 `299.5` 都行，读出来自己处理。后果我在聚合那一节展开。

**单节点的 MongoDB 不支持多文档事务。** 这一条直接决定 Service 层的写法，第三篇那一堆 `@Transactional` 到这里一个都用不上。

并发安全靠单文档原子操作：一条订单的所有改动都在同一个文档里，MongoDB 保证单文档更新原子，判断和写入能在服务端一次做完。我认为这是文档型建模的价值所在，不是为了少写 join。

代价也很实在：明细嵌进订单后，按商品维度统计得先拆数组，单个文档不能无限大，BSON 上限 16MB，明细几十万条就别嵌了。

主键方面，MongoDB 的 `_id` 默认是 ObjectId，12 字节、24 位十六进制，带时间戳和随机数，由客户端生成。

实体里主键我声明成 `String` 而非 `ObjectId`，`/api/orders/{id}` 和前三篇长得一样，不用为序列化多写一层；Spring Data 会自动转，不需要转换器。

ObjectId 不保证递增：按生成时间大致有序，同一秒内多个客户端生成的顺序是随机的。所以「按 id 倒序拿最新那条」在这里不成立，要按时间排就排 `created_at`。

## 4.1.1 在 Boot 4 上的几个变化

版本我先查清楚。`spring-boot-dependencies:4.1.1` 锁的是这些。

| 组件 | 版本 |
|---|---|
| Spring Data MongoDB | 5.1.1 |
| MongoDB Java Driver | 5.8.1 |
| Spring Data Commons | 4.1.1 |
| MongoDB Server（本工程验证环境） | 8.0.32 |

驱动版本这一行我盯了一会儿：Spring Data MongoDB 5.x 要求驱动 5.6 以上，BOM 锁的 5.8.1 一致。这个话题后面还会碰到。

这一篇的第一个重点：**MongoDB 的自动配置拆成了两个模块**。

JPA 那三个模块界限清楚，MongoDB 这两个模块管「连接」和「数据访问」，而这两件事的属性前缀不一样。

| 模块 | 里面装了什么 | 对应的属性前缀 |
|---|---|---|
| `spring-boot-mongodb` | `MongoAutoConfiguration`、`MongoProperties`、`MongoConnectionDetails` | `spring.mongodb.*` |
| `spring-boot-data-mongodb` | Spring Data 那一层，含索引解析与仓储自动配置 | `spring.data.mongodb.*` |

第二列看仔细，这是本篇最要紧的一张表。

import `MongoProperties` 要写 `org.springframework.boot.mongodb.autoconfigure.MongoProperties`；写 `org.springframework.boot.autoconfigure.mongo.*` 这个包会直接编译失败，4.1.1 里没有这个包。我一开始就写错了，编译器当场拦下来的。

### 属性前缀与静默失效

连接属性统一在 `spring.mongodb.*` 前缀下，一共十几项，常用的是 `spring.mongodb.uri`、`host`、`port`、`database`、`username`、`password`、`authentication-database`。

健康检查和指标的前缀也不一样，是 `management.health.mongodb.enabled` 和 `management.metrics.mongodb.*`。

写成 `spring.data.mongodb.uri` 这类旧前缀，后果是静默失效。元数据里标了 `level = error` 和 `since = 4.0.0`，但运行期连警告都没有。

我翻了 `spring-boot-mongodb` 的配置元数据，那族旧属性躺在里面，但每一项只有一个 `name` 和一条 `deprecation`，**没有 `type`，也没有 `sourceType`**。这种条目只是 IDE 提示，管补全和标黄；绑定属性靠 `sourceType` 指向的配置类，而这里没有。

我再去翻 `MongoProperties`，字段只有一套，对应 `spring.mongodb.*`。后来我干脆把 Boot 的 jar 全量扫了一遍，含 `spring.data.mongodb` 这个字面量的 class 一个都没有。

静态推断容易翻车，我于是写了个测试：用 `ApplicationContextRunner` 只起 `MongoAutoConfiguration`，只配旧属性，看框架最终拿到什么连接串。

拿到的是 `mongodb://localhost/test`。不是报错，不是警告，是默认值，配置里那个地址、账号、库名全部落空。

这个测试我留在工程里，`MongoPropertyRenameTest`，六个用例专门验证这件事。踩过的坑写成断言比写成注释有用，注释会被忽略，测试会红。

### 取证的时候还有个坑

取证的时候我顺手加了一条断言，结果它挂了。

判断「配置生效了没有」，我最初用的是 `MongoProperties.determineUri()`。分字段那一组用例，host、port、database、username 逐个断言都过，字段全绑上了，但 `determineUri()` 返回的还是 `mongodb://localhost/test`。

我反编译这个方法，一共三行。

```java
return (uri != null) ? uri : "mongodb://localhost/test";
```

它**只认 `uri` 这一个字段**，host、port、database、username、password 一概不看。分字段写法由另一个类消费：`PropertiesMongoConnectionDetails#getConnectionString()`，配了 uri 就用 uri，否则拿 host 和 port 拼一个连接串。

权威的是 `MongoConnectionDetails#getConnectionString()`，那才是 `MongoClient` 手里拿到的那个串。

断言我全部改到那个接口上，顺便留了一条 `determineUriOnlyLooksAtUri` 当化石，免得以后有人拿它做判断。

顺便记一个驱动行为，我在这里闹过一次笑话：`ConnectionString#getHosts()` 会把默认端口 27017 省掉。`mongodb://localhost/test` 拿到的是 `["localhost"]`，不是 `["localhost:27017"]`，非默认端口才会带上，断言照直觉写在这里就会挂。

### 留在 spring.data.mongodb 前缀下的属性

`spring.data.mongodb.auto-index-creation` 这个配置项**不归连接层管**，仍然在 `spring.data.mongodb` 前缀下。

连接层归 `MongoProperties`，前缀 `spring.mongodb`；Spring Data 层归 `DataMongoProperties`，前缀 `spring.data.mongodb`。两套属性各归各家。

同一个 yml 文件里会出现这种写法。

```yaml
spring:
  mongodb:
    uri: mongodb://root:root123456@127.0.0.1:27017/springboot4_mongo?authSource=admin
  data:
    mongodb:
      auto-index-creation: true
```

上面是连接层，下面是数据层，两个都对。

这里有个很实际的后果，我提醒你一句：批量把 `spring.data.mongodb.` 前缀替换成 `spring.mongodb.` 时，`auto-index-creation` 会跟着被改错，然后它也会静默失效，结果是「代码里写着唯一索引、库里什么都没有」。

仍留在 `spring.data.mongodb` 前缀下的还有 `field-naming-strategy`、`repositories.type`、`representation.big-decimal`、`gridfs.*`，这几个本工程没用到，配置清单里你得记着。

### 测试注解的包名

`@DataMongoTest` 和 `@AutoConfigureDataMongo` 在 `...boot.data.mongodb.test.autoconfigure` 包里。规律跟前三篇一致，包名就是模块路径加上 `.test.autoconfigure`：第一篇 `webmvc.test.autoconfigure`，第二篇 `mybatis.test.autoconfigure`，第三篇 `data.jpa.test.autoconfigure`。

**每换一个技术栈，测试注解的包名都要重新确认一遍**，照抄上一篇的 import 会直接编译失败，我就照抄过一回。

### Spring Data MongoDB 5.x 的几条关键行为

官方迁移指南里有几条直接影响业务代码，我挑出来：`BigDecimal` 和 `BigInteger` 默认按 `DECIMAL128` 转换，且映射配置没有默认值，不显式配置会有告警，UUID 的表示同样没有默认值。这一条不报错，只是存进去的东西长得不一样，你项目里有金额字段的话，跑起来之后去库上看一眼类型。

`@Meta.allowDiskUse` 的类型是 `String`，管大聚合的内存限制，用到的项目不多。

JMX 这边没有支持，你手里要是有基于 JMX 的监控采集脚本，会直接失效。

还有一条，`DefaultMessageListenerContainer` 自动启动默认值是 `true`，属消息监听那边，跟 MongoDB 关系不大，顺带提一句。

## 依赖和配置

依赖比第三篇还短，官方 starter 直接用就够。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb-test</artifactId>
    <scope>test</scope>
</dependency>
```

注意最后一个，测试 starter 是独立坐标 `spring-boot-starter-data-mongodb-test`，JPA 那篇没有这个东西。

配置长这样，这是我能跑通全篇的最小集。

```yaml
spring:
  mongodb:
    uri: mongodb://${MONGO_USER:root}:${MONGO_PASSWORD:root123456}@${MONGO_HOST:127.0.0.1}:${MONGO_PORT:27017}/${MONGO_DB:springboot4_mongo}?authSource=admin
  data:
    mongodb:
      auto-index-creation: true
  mvc:
    problemdetails:
      enabled: true
  jackson:
    default-property-inclusion: non_null
```

账号密码走环境变量，默认值是本机地址，你直接跑之前记得改。

`auto-index-creation` 这一行是这一篇能跑通的前提，下面单独讲。

`problemdetails` 和 `jackson` 那两行沿用前三篇，错误响应还是 RFC 9457 那套。

还有一行日志配置放在最后，平时别开。

```yaml
logging:
  level:
    org.mongodb.driver.protocol.command: DEBUG
```

打开它能看到驱动发出去的每一条命令原文。这一篇有两处结论，就是靠它拿到的。

## 核心代码

### 索引这件事，默认是不做的

实体上声明索引很简单，但它最容易被忽略、代价也最实在。

```java
@Document(collection = "orders")
@CompoundIndex(name = "idx_status_created_at", def = "{'status': 1, 'created_at': -1}")
public class Order {

    @Indexed(unique = true, name = "uk_order_no")
    @Field("order_no")
    private String orderNo;

    @Indexed(name = "idx_status")
    @Field("status")
    private OrderStatus status;
}
```

三处声明，一个唯一索引，一个单字段索引，一个复合索引。

**默认情况下，这三个索引一个都不会被创建。**

![](https://static.xiongneng.me/index-not-created-by-default-20260921220212.png)

`@Indexed` 只是一份索引定义，去建索引的是 `spring.data.mongodb.auto-index-creation` 这个开关，而它的默认值是 `false`。

这件事有多难受，我专门做了一遍给你看：开关关掉又跑了一遍，工程里留了一个反例测试 `MongoIndexCreationOffTest`，用另一个库做隔离。

结果：文档正常写入，`findByOrderNo` 正常返回，一切看着都好。我数了一下索引，除 MongoDB 自带的 `_id` 什么都没有。然后我拿同一个订单号再插一条，**插进去了**。唯一约束不存在。

代码里白纸黑字写着 `@Indexed(unique = true)`，业务上却没有唯一性保证，全程没有任何地方会报错。日志干净，响应正常，只有数据是错的。

反向验证我也做了，把索引列出来看。

```
_id_                      {_id: 1}
idx_status_created_at     {status: 1, created_at: -1}
uk_order_no               {order_no: 1}  [unique]
idx_status                {status: 1}
```

三个都在，方向也对，`uk_order_no` 带着 unique 标记。

这三个索引不是凭空冒出来的。我把驱动命令日志打开，应用启动时发了三条 `createIndexes`。

```
{"createIndexes": "orders",
 "indexes": [{"key": {"status": 1, "created_at": -1}, "name": "idx_status_created_at"}]}

{"createIndexes": "orders",
 "indexes": [{"key": {"order_no": 1}, "name": "uk_order_no", "unique": true}]}

{"createIndexes": "orders",
 "indexes": [{"key": {"status": 1}, "name": "idx_status"}]}
```

服务端的回执我也一起看了，索引数依次是 1、2、3、4，第一条还顺带把集合建了出来。

从声明到建索引的每一步都对上了：`@Indexed` 和 `@CompoundIndex` 声明，映射层收集，索引解析器处理，启动时下发 `createIndexes`。中间任何一环断了，都是「代码里有、库里没有」这个结局。

还有一个细节，复合索引的 `def` 里必须写 `created_at`，不能写 `createdAt`：这个字符串**原样发给 MongoDB**，MongoDB 只认文档里真实存在的字段名，而这个类把 `createdAt` 映射到了 `created_at`。写成 `createdAt` 不会报错，索引照样建出来，只是永远用不上，唯一能发现的地方是 `explain()` 里看它没进 winningPlan。

### 字段名映射，以及混在同一个类里的两套规则

`@Field("order_no")` 这类注解把 Java 属性名和文档字段名对上。我刻意改成下划线风格，是为了让同一套数据模型在 MySQL 和 MongoDB 里字段名对得上，对照前两篇的 SQL 不用再做映射。

代价是，**这个类里同时存在两套命名规则**。

派生查询的方法名用 Java 属性名，`findByOrderNo`、`countByStatus`、`findByTotalAmountGreaterThanEqual`，这些都是属性名。手写的查询、聚合、索引定义，用的是 `@Field` 指定的存储名。

这个界线是这一篇最容易写错的地方，`@Query` 那一节有我翻车的实例。

### `@Transient` 漏了会出一个幽灵字段

明细里有一个计算字段。

```java
public class OrderItem {

    @Field("product_name")
    private String productName;

    private BigDecimal price;

    private int quantity;

    @Transient
    public BigDecimal getAmount() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
```

`amount` 不在数据库里，它是算出来的，只出现在接口响应里。

注意这个 `@Transient` 来自 `org.springframework.data.annotation`，**不是** `jakarta.persistence.Transient`：两个都合法，IDE 不提示，但用错了不生效。

后果是 `amount` 变成一个落库的真实字段，值是第一次写入时算出来的，之后再也不会更新。你改一下数量，库里那个 `amount` 还是老数字。一个不报错、不同步的幽灵字段，查库的时候直接看原始文档最清楚。

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

`items` 里只有 `product_name`、`price`、`quantity`，没有 `amount`。这是对的。

另外两处顺便看：字段名全是蛇形，接口返回的却是驼峰，说明 `@Field` 只管存储、不影响序列化；金额是 `Decimal128` 不是 `Double`，对应 5.x 的默认映射行为，`299.50` 不会变成 `299.5`，这一条对存金额的项目来说是稳稳的赚。

`_class` 那一项是 Spring Data 用来支持多态映射的，属正常现象。

### 仓储，以及手写的查询到底会不会被翻译

派生查询不用讲，跟第三篇一模一样，方法名即查询。

手写的部分挺有意思：我最初判断 `@Query` 里那段 JSON 是裸字符串，下发前不会有任何改动，就在仓储里写了两个方法做对照。

```java
@Query("{ 'items.product_name': ?0 }")
List<Order> findByItemProductName(String productName);

@Query("{ 'items.productName': ?0 }")
List<Order> findByItemProductNameWithPropertyName(String productName);
```

一个写存储名，一个写属性名。我当时的判断：前者能查到，后者查不到。

测出来是两个都能查到。

Spring Data 在下发前把能对上属性的路径翻译成了存储名，`items.productName` 被翻成 `items.product_name`，「裸字符串原样下发」的假设不成立。

顺着往下试，结论一致：

`Criteria.where("items.productName")`，下发的是 `items.product_name`。

`Update.set("customerName", ...)`，落到 `customer_name` 上，而且不会多出一个同义字段。

这里还有个反过来的陷阱。**`org.springframework.data.mongodb.core.MongoTemplate` 的 DEBUG 日志打的是翻译前的 Query，不能当作「下发了什么」的证据。**

这层日志打开，按 id 查一条订单，输出是这样：

```
findOne using query: { "id" : "6aad2e3f603b044e25ffe593"} fields: Document{{}}
    for class: class com.xncoding.mongo.domain.Order in collection: orders
```

`id`。库里那个字段叫 `_id`。我照着这行去 pymongo 里试，一条都查不出来。

报文要去驱动层看：

```
Command: {"find": "orders", "filter": {"_id": {"$oid": "6aad2e3f603b044e25ffe593"}},
          "limit": 1, "singleBatch": true, "$db": "springboot4_mongo"}
```

同一句话，两个日志两个样子。`MongoTemplate` 打的是转换之前的 `Query` 对象，属性名到存储名的转换发生在再往下一层，所以日志里 `id` 还是 `id`。

要拿日志当证据，**只有 `org.mongodb.driver.protocol.command` 那一层算数**：中间层的日志看着最像人话，最容易当成结论用，而它恰恰是转换之前那一步的快照。我现在只认驱动层的 `Command: {...}`，这是我的铁证标准。

从属性名这一侧思考基本是安全的，例外是下面那个聚合别名的坑。

那个用例叫 `queryAnnotationDoesTranslateFieldNames`，断言反过来写，测「两种写法结果一致」。

### 聚合，以及这一篇最隐蔽的坑

状态统计那个查询我先用 `@Aggregation` 写。

```java
@Aggregation(pipeline = {
        "{ $group: { _id: '$status', count: { $sum: 1 }, totalAmount: { $sum: '$total_amount' } } }",
        "{ $sort: { _id: 1 } }"
})
List<OrderStatusStat> statByStatus();
```

返回类型上要对两处做映射。

```java
public class OrderStatusStat {

    @Field("_id")
    private OrderStatus status;

    private long count;

    @Field("total_amount")
    private BigDecimal totalAmount;
}
```

`_id` 是固定的，`$group` 之后分组字段一定叫 `_id`，躲不掉。

`total_amount` 那个就出事了。

测试跑出来 `totalAmount` 是 null。我去查报文，打开驱动命令日志，抓下发给服务端的 `$group` 原文。

```
注解版：
{"$group": {"_id": "$status", "count": {"$sum": 1},
            "total_amount": {"$sum": "$total_amount"}}}
```

注解里写的是 `totalAmount`，下发出去变成了 `total_amount`。

**`@Aggregation` 会把管道里的输出别名也当属性名翻译一遍。**

同一个管道里的 `count` 没被改，因为实体里没有叫 `count` 的属性，翻译层只处理能对上的。

那 fluent API 呢，就是 `Aggregation.group().sum().as()` 那一套。

这里我犯过一个方法上的错误：为了让两条路径结果一致，我把 fluent 那侧的 `.as()` 也写成了存储名 `total_amount`，改完测试全绿。

再看报文，两条管道下发出去长得一模一样，什么都比不出来。

注解版被翻译之后的结果，恰好等于手写的那个正确值。这种「正确写法看不出问题」的情况，需要一个反例才能把差别暴露出来。

于是我加了个故意的反例接口。

```java
public List<OrderStatusStat> statByStatusNaive() {
    Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.group("status")
                    .count().as("count")
                    .sum("total_amount").as("totalAmount"),
            Aggregation.sort(Sort.Direction.ASC, "_id"));
    return mongoTemplate.aggregate(aggregation, Order.class, OrderStatusStat.class)
            .getMappedResults();
}
```

跟正例只差一个名字，`.as()` 里写的是实体属性名 `totalAmount`，看着比 `total_amount` 自然多了。

现在源码里两个地方写的都是 `totalAmount`，一个在注解里，一个在 `.as()` 里。报文是这样。

```
注解版：  {"$group": {"_id": "$status", "count": {"$sum": 1},
                     "total_amount": {"$sum": "$total_amount"}}}

反例代码版：{"$group": {"_id": "$status", "count": {"$sum": 1},
                     "totalAmount": {"$sum": "$total_amount"}}}
```

源码一样，发出去的报文不一样。

![](https://static.xiongneng.me/aggregation-alias-two-paths-20260921220212.png)

答案清楚了：`@Aggregation` 那条路翻译输出别名，fluent API 不翻译，写什么发什么。

结论有点反直觉：**注解版里可以写属性名，代码版里必须写存储名。** 两套规则不一样。

反例接口留在工程里，`/api/orders/stats/naive`，你可以三个接口一起调。

```
注解版    ：[{"count":1,"status":"CANCELLED","statusLabel":"已取消","totalAmount":86.00},
            {"count":2,"status":"CREATED","statusLabel":"已创建","totalAmount":1198.50},
            {"count":2,"status":"PAID","statusLabel":"已支付","totalAmount":2468.00}]

代码版    ：（逐字一致）

反例代码版：[{"count":1,"status":"CANCELLED","statusLabel":"已取消"},
            {"count":2,"status":"CREATED","statusLabel":"已创建"},
            {"count":2,"status":"PAID","statusLabel":"已支付"}]
```

`count` 一样，`status` 一样，`totalAmount` 整列消失。

原因是 `OrderStatusStat.totalAmount` 上标着 `@Field("total_amount")`，映射层只认这个键，而 fluent API 把 `totalAmount` 原样发了出去，这个键就悬在结果文档里没人取。

**没有报错，没有警告，字段就这么没了。**

浏览器里打开这个反例接口，`count` 和 `status` 排得整整齐齐，金额那一列不存在。

![](https://static.xiongneng.me/browser-stats-naive-20260918202746.png)

留这个接口就是让你自己 curl 一次，第三篇那个 `/summary/projection` 也是这么个角色。

### 模板层，以及没有事务之后怎么写

`OrderMongoDao` 里是几个我用 `MongoTemplate` 手写的地方，这一篇的并发安全全在这里。

分页还是老样子，`Query` 加 `Pageable`，我不把 `Page` 直接当契约，外面包一层统一的分页响应体。

状态流转是重点。

```java
mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(id).and("status").is(expected)),
        new Update().set("status", target).inc("version", 1),
        FindAndModifyOptions.options().returnNew(true),
        Order.class);
```

第三篇里这个是「在事务里读出实体、判断、改、提交」，靠事务隔离挡并发。这里没有事务可用，所以我换成了把「读到的状态」变成「更新的条件」。

条件里带上 `status`，别人在这中间改了状态，这次更新就匹配不到任何文档，返回 null，服务层据此抛 422。

**判断和写入之间没有任何窗口**，因为它们在服务端是同一条命令。

明细的增删也一样。

```java
// 追加
mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
        new Update().push("items", item), Order.class);

// 移除
mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
        new Update().pull("items", Query.query(Criteria.where("product_name").is(productName))),
        Order.class);
```

如果按前两篇的思路写，这两步会是「读出订单、改 items 列表、整体 save 回去」。差别不止是少传点数据：整份 save 回去就是拿旧快照覆盖新状态，两个人同时加明细，后提交的会覆盖先提交的。`$push` 没有这个问题。

`$pull` 按条件移除**所有**匹配的元素，同名商品有两条会一起被删掉。接口返回的 `removed` 是「影响了几条文档」，不是「删了几个元素」，本工程里这两个值恰好都是 1，容易看混。

商品销量排行那个查询也是模板层。

```java
Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.unwind("items"),
        Aggregation.group("items.product_name")
                .sum(ArithmeticOperators.Multiply
                        .valueOf("items.price")
                        .multiplyBy("items.quantity")).as("salesAmount")
                .sum("items.quantity").as("totalQuantity"),
        Aggregation.sort(Sort.Direction.DESC, "totalQuantity"),
        Aggregation.limit(limit));
```

这是文档型存储最不直观的一步：明细是订单里的数组字段，按商品粒度统计得先用 `$unwind` 把数组拆开，一条含 2 个商品的订单变成 2 条记录，每条带一个商品。

等价于关系型的 `join t_order_item`，但不需要提前把明细建模成独立实体。

代价是中间结果会变大，订单里明细越多越明显，所以 `$unwind` 之后通常紧跟着 `$group` 把行数收回来。

这个方法的 `.as()` 写成了 `"total_amount"` 这种存储名，看着别扭。原因就是前面那个别名坑，得跟注解版落在同一个键名上。

### Service 层一个 `@Transactional` 都没有

第三篇的 Service 层每个写方法都有 `@Transactional`，读方法标 `readOnly`。这一篇一个都没有，不是漏了，是单节点 MongoDB 不支持多文档事务。

写了会怎样？不会报错，Spring 也认，事务管理器在那儿，但它拦不住任何东西。更糟的是它给人一种「有事务保护」的错觉。

那并发安全靠什么？

| 场景 | 做法 | 保证 |
|---|---|---|
| 整体更新 | `save()` 加 `@Version` | 乐观锁，版本冲突抛 409 |
| 状态流转 | `findAndModify` 加当前状态作条件 | 判断与写入服务端原子完成 |
| 追加明细 | `$push` | 服务端改数组，不覆盖整份文档 |
| 移除明细 | `$pull` | 同上 |

四条全部依赖同一个前提，**业务操作能收进一个文档里**。这也是文档型建模的取舍所在。

![](https://static.xiongneng.me/single-document-atomicity-20260921220212.png)

真要跨文档事务，得上副本集，还得显式配 `MongoTransactionManager`。本工程单节点，不涉及。

### 异常类型和第三篇对不上

| 场景 | JPA（第 3 篇） | MongoDB（本篇） |
|---|---|---|
| 乐观锁冲突 | `ObjectOptimisticLockingFailureException` | `OptimisticLockingFailureException`（父类） |
| 唯一键冲突 | `DataIntegrityViolationException` | `DuplicateKeyException`（子类，错误码 11000） |

乐观锁这一组是子类到父类，唯一键那一组是父类到子类，方向是反的。

我把第三篇的全局异常处理器复制过来改，`getPersistentClassName()` 那一行直接编译失败。父类没有这个方法。

这个报错比读继承关系快多了，父子关系自己写一遍比看十遍记得牢。

## 完整案例，跑起来看

这一节我从头走一遍，所有响应都是真实请求的原文，跟我贴的命令一一对应。

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

`Location` 里那串就是 ObjectId。响应体里的 `amount` 是算出来的，库里没有这个字段。

### 乐观锁，两次更新

```bash
# 第一次，带上当前版本号 0，200，版本号变成 1
curl -i -X PUT http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":888.00,
       "remark":"改了金额","version":0}'

# 第二次，还拿版本号 0，这个已经过期了，409
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

抛的是 `OptimisticLockingFailureException`，不是 JPA 那边那个。

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

状态机本身跟第三篇是同一套，`CREATED` 换到 `PAID` 或者 `CANCELLED`，走完了就是终态。变的只是实现，第三篇靠事务，这里靠 `findAndModify`。

![](https://static.xiongneng.me/status-transfer-422-20260918202745.png)

### 明细的原子增删

```bash
curl -i -X POST http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597/items \
  -H "Content-Type: application/json" \
  -d '{"productName":"鼠标垫","price":18.00,"quantity":3}'

curl -i -X DELETE "http://localhost:8080/api/orders/6aad2e3f603b044e25ffe597/items?productName=鼠标垫"
```

```
HTTP/1.1 200
Content-Type: application/json

{"removed":1}
```

### 商品销量排行

```bash
curl -s "http://localhost:8080/api/orders/top-products?limit=3"
```

```
[{"productName":"腕托","totalQuantity":4,"salesAmount":100.00},
 {"productName":"机械键盘","totalQuantity":3,"salesAmount":1586.50},
 {"productName":"鼠标垫","totalQuantity":2,"salesAmount":36.00}]
```

这份数据里机械键盘的销售额比腕托高一个数量级，但销量少一个。5 条订单的明细摊平之后是 7 条中间记录，`$group` 收成 4 个商品，`limit` 只留前 3 个。

### 接口投影，以及它的反例

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

接口投影只查声明了的字段，换存储也能用。第三篇是同一套写法，那时「只查四列」，这里「只查四个字段」。

浏览器里打开接口投影那个地址，回来的就是这个，每个字段都在。

![](https://static.xiongneng.me/browser-interface-projection-20260918202746.png)

反例这一版用手写 `fields` 投影只查了四个字段，却返回 `OrderResponse`。结果是 `remark`、`version`、`createdAt`、`updatedAt` 全是 null，`items` 是个空列表。

字段一个不少地出现了，里面没有内容。

留这个接口就是因为这件事很现实：**一个看似正常、实为空壳的字段，比直接缺字段更容易骗过联调。** 你前端拿到「客户名对、金额对、备注空」的数据，排查半天才发现是投影写窄了。

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

服务层先用 `ObjectId.isValid` 挡了一道。

不挡的话，驱动收到转不了的字符串会抛 `IllegalArgumentException`，结果是 500。一个拼错的 URL 换来一条服务器错误日志，查起来很浪费时间，这是主键换成 ObjectId 之后新增的一类问题，前两篇不会有。

![](https://static.xiongneng.me/invalid-objectid-404-20260918202748.png)

### 唯一索引在拦

订单号是服务端生成的，接口层面造不出重复，所以这一步直接绕过应用写一次库。

```
被拦下：DuplicateKeyError: E11000 duplicate key error collection:
springboot4_mongo.orders index: uk_order_no dup key:
{ order_no: "ORD202609182027433760839" }
```

错误码 11000，对应 `org.springframework.dao.DuplicateKeyException`，全局异常处理器把它映射成 409。

同一个库，同一个 `orders` 集合，同一个插入动作。开关关掉的时候它成功了，打开的时候它被拦住。

### 查库对账

索引和原始文档都看过了，再对总数和字段类型。我本机是社区版服务端，`bin` 目录里只有 `mongod` 和 `mongos`，没有 `mongosh`，所以我用 pymongo 写了一段小脚本直接查，脚本留在工程里，下面贴的就是它的输出。

```
_id                                ObjectId
order_no                           str
customer_name                      str
customer_phone                     str
total_amount                       Decimal128
status                             str
remark                             str
version                            Int64
created_at                         datetime
updated_at                         datetime
items                              array[1]
items[].product_name               str
items[].price                      Decimal128
items[].quantity                   int
_class                             str
```

全蛇形，全是对应类型，`items` 里没有 `amount`。我删掉一条之后总数是 4。

## 这一篇的测试怎么写

```bash
mvn test
```

```
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

| 测试类 | 技术 | 覆盖内容 |
|---|---|---|
| `OrderRepositorySliceTest` | `@DataMongoTest` | 16 个用例，索引自动创建、唯一索引拦截、字段名映射、`@Query` 字段名翻译、`Update` 翻译、内嵌数组、`$push`/`$pull`、聚合两种写法对照、分页、两种投影 |
| `OrderApiIntegrationTest` | `@SpringBootTest` 加 `MockMvcTester` | 36 个用例，CRUD、分页与排序、乐观锁、状态机、原子增删、真删、参数校验、problem 文档结构 |
| `MongoPropertyRenameTest` | `ApplicationContextRunner` | 6 个用例，旧属性静默失效、新属性正常绑定、uri 优先级 |
| `GlobalExceptionHandlerTest` | 直连 handler | 4 个用例，`DuplicateKeyException` 到 409、`OptimisticLockingFailureException` 到 409 |
| `MongoIndexCreationOffTest` | `@DataMongoTest` 加关闭开关 | 1 个用例，反面对照，索引一个都不建 |

测试写法上有四处跟第三篇不一样，都是被 MongoDB 本身逼出来的，我逐条说。

**没法用 `@Transactional` 回滚。** 第三篇的切片测试靠事务回滚隔离用例，这一篇单节点不支持多文档事务，事务管理器不介入，所有清理我都在 `@BeforeEach` 里手动清集合。

**也不该换成内存库。** 这一篇要验证的索引创建、唯一约束、驱动错误码、`Decimal128` 精度全是服务端行为，换成内存实现就是在测一个仿制品，嵌入式 MongoDB 的版本往往还比服务端旧好几个大版本。

**连不上就整体跳过。** 沿用前几篇的处理，用例执行前先探一次 `ping`，连不上直接 skip。你机器上没配 MongoDB 的话，跑起来会看到 `Skipped`，不会看到一片红。

**`@DataMongoTest` 不扫自定义 `@Repository`。** 这上面我栽过一次。

`OrderMongoDao` 是自定义的类，标了 `@Repository`。我按直觉在切片测试里注入它，直接报 `NoSuchBeanDefinitionException`。

`@DataMongoTest` 收的是「Spring Data 仓储」，即 `OrderRepository` 这种由框架生成代理的接口，自己写的数据访问类一个都不扫，要显式加一行。

```java
@DataMongoTest
@Import(OrderMongoDao.class)
class OrderRepositorySliceTest {
```

## 避坑指南

按我踩到的顺序整理成十三条。

**坑一，连接属性写成旧前缀是静默失效，不是「还能用但会警告」。** 验证不能看配置写得对不对，得看框架最终拿到什么：写成 `spring.data.mongodb.uri` 会安安静静连到 `mongodb://localhost/test`，表现为「连不上库」或「连上了但数据不见了」，而 yml 看上去完全正确。第一次碰到这种「连不上库」，九成是这个原因。

**坑二，`MongoProperties.determineUri()` 不能用来判断配置生效了没有。** 它只有三行，只认 `uri` 这一个字段，一律返回 `mongodb://localhost/test`；权威的是 `MongoConnectionDetails#getConnectionString()`。

**坑三，`spring.data.mongodb.auto-index-creation` 不归连接层管。** 批量把 `spring.data.mongodb.` 替换成 `spring.mongodb.` 时它会跟着被改错、静默失效；同样留在老前缀下的还有 `field-naming-strategy`、`repositories.type`、`representation.big-decimal`、`gridfs.*`。

**坑四，`@Indexed` 默认什么索引都不建。** `auto-index-creation` 默认值是 `false`，忘了打开唯一约束就不存在，重复数据能进去，全程没有提示。上线前拿 `db.orders.getIndexes()` 核对一遍最省事。

**坑五，聚合输出的别名，两条实现路径的规则不一样。** `@Aggregation` 翻译别名，fluent API 写什么发什么，写错的表现是金额那一列静默消失，只能看报文发现。

**坑六，取证方案本身要设计对，不然什么都证明不了。** 对照那侧改成「正确的值」，两条路径就一模一样；想证明某一层做了转换，得让输入相同、机制不同，反例不能省。

**坑七，`@Transient` 要用 Spring Data 那个包。** `jakarta.persistence.Transient` 不生效，计算字段会变成落库的真实字段且永不同步，查一次原始文档就能发现。

**坑八，复合索引的 `def` 里必须写存储名。** 写 `createdAt` 不报错但索引永远用不上，只能在 `explain()` 里发现它没进 winningPlan。

**坑九，单节点没有跨文档事务，别写 `@Transactional` 图安心。** 它拦不住任何东西，只会给人「有事务保护」的错觉；并发安全全部放在单文档原子操作上，收不进一个文档就得考虑副本集或重新建模。

**坑十，异常类型和 JPA 侧对不上，而且方向是反的。** 乐观锁是子类到父类，唯一键是父类到子类；照抄第三篇的处理器，`getPersistentClassName()` 会直接编译失败。

**坑十一，主键换成 ObjectId 之后多出来一类问题。** 不合法的 id 会让驱动抛 `IllegalArgumentException` 变 500，服务层先用 `ObjectId.isValid` 挡成 404；ObjectId 也不保证递增。

**坑十二，金额字段的默认表示是 Decimal128。** 同一字段混进字符串类型数据时比较和聚合会出偏差，库里已有别的类型表示的金额要先确认；这个错误不报，只会让结果差一点点。

**坑十三，中间层的 DEBUG 日志不是「下发了什么」。** `MongoTemplate` 打的是翻译前的 `Query`，`findById` 显示成 `id`，照着查库一条都对不上；拿日志当证据只认驱动层 `org.mongodb.driver.protocol.command` 的 `Command: {...}`。

## 小结

这一篇的核心是一句话：**MongoDB 这条链上「不报错的隐式行为」比关系型多得多**。说实话，写完这一篇我回头看关系型那两篇的报错，都觉得亲切了，人家至少肯给你一条异常。

三处静默组成了这一篇的主要篇幅。配置静默失效，写成 `spring.data.mongodb.uri` 它不读，也不提示，直接连默认库。索引静默不建，`@Indexed` 躺在那儿，库里什么都没有，重复数据照收。别名静默被改，注解里写 `totalAmount` 下发成 `total_amount`，换一种写法又不变了。关系型那两篇的坑多数会给一个异常或一条错日志，能顺着往上查；这一篇的三个连痕迹都没有，只有对比才能发现，所以我留了两个反例接口和一个专门验属性前缀的测试类。

还有一处静默是给排查本身设的：中间层日志打的是转换前的 Query，`findById` 显示成 `id`，看着完全正常，照着它去查库一条都对不上。取证那一步也得留个心眼，只认驱动层报文。

Spring Boot 4 下这个主题的现状，整理成一张表，你选型时对着查。

| 主题 | 现状 |
|---|---|
| 自动配置 | 拆在 `spring-boot-mongodb` 和 `spring-boot-data-mongodb` 两个模块里 |
| 连接属性 | `spring.mongodb.*` 前缀；写成 `spring.data.mongodb.*` 静默失效 |
| 数据层属性 | `auto-index-creation` 等仍在 `spring.data.mongodb` 前缀下 |
| 健康与指标 | `management.health.mongodb`、`management.metrics.mongodb.*` |
| 测试注解 | `org.springframework.boot.data.mongodb.test.autoconfigure` |
| 测试 starter | `spring-boot-starter-data-mongodb-test`（4.1.1 提供） |
| `BigDecimal` / `BigInteger` | 默认 `DECIMAL128`，映射配置没有默认值 |
| UUID 表示 | 没有默认值 |
| `@Meta.allowDiskUse` | `String` |
| JMX | 不支持 |
| `DefaultMessageListenerContainer` | 默认自动启动 |

选型建议我是这么给的：如果你的模型能收进单个文档、并发冲突靠单文档原子操作就兜得住，MongoDB 这条链在 Boot 4 上是成熟的，starter、测试注解、异常体系都齐了，放心用，工程放在 `springboot4-mongodb`，从建库到查库对账的完整命令都在 README 里。要是你的写操作经常跨两个以上的「集合级实体」，或者强依赖跨文档事务，别硬上，要么上副本集加 `MongoTransactionManager`，要么回关系型。带金额字段的项目，跑起来之后去库里确认一眼 `Decimal128` 的映射，那是 5.x 唯一一个不显式配置会告警的地方。

真正没解决的事有两件。一是我的树莓派跑不了新版 MongoDB：那台是 4B，Cortex-A72，ARMv8.0-A，没有 LSE 原子指令，而 MongoDB 从 5.0 开始要求 ARMv8.2-A，容器起不来，退出码 132；降到 4.4.18 能起来，但 4.4 已经 EOL，我不愿意为一台机器锚一个停止维护的版本，所以这一篇的 MongoDB 在我本机 Windows 上，`127.0.0.1:27017`，MySQL、Redis、RabbitMQ 还是在那台 Pi 上，拓扑跟前几篇不一样。二是单节点之外的副本集事务我没有实测，`MongoTransactionManager` 那条路留到我有多机环境再补。

## 参考链接

- [Spring Data MongoDB 官方参考文档](https://docs.spring.io/spring-data/mongodb/reference/)
- [Spring Boot 4.1 Reference - MongoDB](https://docs.spring.io/spring-boot/4.1/reference/data/nosql.html#data.mongodb)
- [MongoDB 官方手册](https://www.mongodb.com/docs/manual/)
