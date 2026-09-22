---
title: SpringBoot4系列03 - 集成Spring Data JPA与Hibernate 7
slug: sb4-jpa
date: 2026-09-19 22:00:00 +0800
toc: true
categories: [ java ]
tags: [ SpringBoot, SpringBoot4, JPA, Hibernate, MySQL ]
draft: false
---

上一篇结尾我留了个约定：`OrderRepository` 还要再换一次实现，如果接口还撑得住，分层就算做对了。这篇换 Spring Data JPA，我来兑现这句话。

结论先放这儿：接口契约一个字没改，Controller、DTO、状态机、错误响应全部原样。但说实话，这是系列里坑最多的一篇，比前两篇加起来还多，而且大多不在「报错」，在「悄悄按另一套规则跑」，等你发现时数据已经不对了。

最大的坑跟 JPA 没关系。我按老习惯去数 SQL 日志里的 `Hibernate:` 行数，数出来是 0。一条 SQL 都没发？应用明明在正常工作。对着日志看了半天才反应过来：Hibernate 7 的 SQL 日志走 `org.hibernate.SQL` 这个 logger，行首没有 `Hibernate: ` 前缀，`grep "Hibernate:"` 数出来恒等于 0。拿着错的前提，之后所有推断都会被带偏，这事后面单独说。

代码全部可跑，工程在 `springboot4-jpa` 目录下，还是那台树莓派上的 MySQL，建表脚本一条命令跑完。

## JPA、Hibernate、Spring Data JPA 这三层

这几个名字经常被混用，但它们是三件东西，各管一段。

![](https://static.xiongneng.me/jpa-layer-position-20260921213912.png)

最下面是 **Hibernate**，JDBC 之上的 ORM 实现，把对象图翻译成 SQL、管理持久化上下文、做脏检查。只引它加 JDBC 也能跑，配置和事务自己管。

中间是 **Jakarta Persistence**，常说的 JPA，**规范**，只定义接口和注解，`EntityManager`、`@Entity`、`@Version`、`@ManyToOne` 都是它定的。Hibernate 是实现之一，主流到大家默认 JPA 就是 Hibernate。

最上面是 **Spring Data JPA**，规范之上再包的一层，解决「怎么少写代码」：`OrderRepository extends JpaRepository` 白拿 CRUD 和分页，方法名写 `findByOrderNo` 就能生成查询，跟数据库方言没关系。

我排查问题时第一步就是分清在查哪一层：报错来自 Hibernate，注解来自 Jakarta Persistence，代码风格来自 Spring Data。搜索词带对了，能省一半时间。

改造成本也由分层决定：MyBatis-Plus 的注解（`@TableName`、`@TableField`、`@TableLogic`）全部失效；`OrderStatus` 状态机、`OrderPageQuery` 白名单、DTO 组装这些纯业务代码原封不动。

![](https://static.xiongneng.me/carry-over-boundary-20260921213912.png)

贴数据库的层重写，贴业务的层不动。把这条界线画出来之后，工作量就清楚了：要重写的只有中间一层。

## 4.1.1 的几个关键事实

版本号我先查清楚，`spring-boot-dependencies:4.1.1` 的 BOM 锁的是下面这些。别抄二手资料，`mvn dependency:tree` 跑一遍最快。

| 组件 | 版本 |
|---|---|
| Hibernate ORM | 7.4.5.Final |
| Jakarta Persistence | 3.2.0 |
| Hibernate Validator | 9.1.3.Final |
| Spring Data BOM | 2026.0.1 |

这一篇的重点：**JPA 的自动配置拆成了三个模块**。

| 模块 | 里面装了什么 |
|---|---|
| `spring-boot-jpa` | `JpaProperties`、`JpaBaseConfiguration`、`EntityManagerFactoryBuilder` |
| `spring-boot-hibernate` | `HibernateJpaAutoConfiguration`、`HibernateMetricsAutoConfiguration`、`HibernateProperties` |
| `spring-boot-data-jpa` | `DataJpaRepositoriesAutoConfiguration` |

写成 `org.springframework.boot.autoconfigure.orm.jpa.*` 编译直接过不去；要自定义 `EntityManagerFactory`、继承 `JpaBaseConfiguration`、import `JpaProperties`，类都从上表三个模块里找。我第一次 import 就是按老包名敲的，IDE 没给提示，我还以为是索引没建好。

测试注解散成三个包，IDE 补全帮不上忙，只能去制品里翻。

| 注解 | 4.1.1 所在包 |
|---|---|
| `@DataJpaTest` | `...boot.data.jpa.test.autoconfigure` |
| TestEntityManager | `...boot.jpa.test.autoconfigure` |
| `@AutoConfigureTestDatabase` | `...boot.jdbc.test.autoconfigure` |
| `@AutoConfigureMockMvc`（第 1 篇的） | `...boot.webmvc.test.autoconfigure` |

**每换一个技术栈，测试注解的包名都得重新确认一遍**，照抄上一篇的 import 每篇都编译失败，我在这篇就栽过。

两个配置项跟直觉不一样。`spring.jpa.open-in-view` **默认还是 `true`**：`EntityManager` 绑定整个请求生命周期，Controller 里碰懒加载集合不报错，代价是每请求白占一个数据库连接，慢查询被藏起来。我在配置里关了它，然后撞上一个必踩的错，后面讲。

`spring.jpa.defer-datasource-initialization` **还在生效**，但元数据搜不到：它不是 `JpaProperties` 的字段，由 `JpaDependsOnDatabaseInitializationDetector` 从 `Environment` 直读。按「字段没了加元数据搜不到」会推出「配置被删了」，写进 yml 照样起作用。我的结论：判断配置项还在不在，得看字节码。

还有 `spring.jpa.bootstrap`，取值 `DEFAULT` 或 `ASYNC`，控制 `EntityManagerFactory` 初始化方式；选 `ASYNC` 要自己提供 `BootstrapExecutor`，不然抛 `BootstrapExecutorRequiredException`。本工程没用到，提一句备查。

## 依赖和配置

JPA 不需要第三方 starter，官方第一个就够。

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
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

四个依赖各管各的：webmvc 承载接口，validation 接住参数校验，data-jpa 把 Hibernate 和 Spring Data 一起带进来，MySQL 驱动只在运行期用。

测试依赖单独引。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
```

`spring-boot-starter-data-jpa-test` 自带四样：`spring-boot-starter-data-jpa`、`spring-boot-starter-test`、`spring-boot-starter-jdbc-test`、`spring-boot-data-jpa-test`，前面三个测试注解从这里出来。

配置的关键几行。

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
        jdbc:
          time_zone: Asia/Shanghai
    show-sql: false

logging:
  level:
    org.hibernate.SQL: DEBUG
    org.hibernate.orm.jdbc.bind: TRACE
```

`ddl-auto` 给 `validate` 而不是 `none`：启动时对一遍实体和表结构，对不上直接启动失败。错位通常跑到某条 SQL 才暴露，那时排查成本已经出去半天。

`show-sql` 配 `false`，SQL 日志走 `logging.level`；它的输出和 `org.hibernate.SQL` logger 是同一份，决定能不能看到 SQL 的是 logger 级别。

这里**没有**配 `spring.sql.init`：它会在启动时自动执行建表脚本，`schema.sql` 里有 `DROP TABLE`，配上就是每次启动清一次库。建表脚本手动执行一次，写在 README。第 2 篇提过这个坑，抄配置时容易顺手带上。

## 核心代码

### 实体上的注解

先看实体，跟上一篇并排一比就是两套规则。

![](https://static.xiongneng.me/entity-mapping-20260921213912.png)

```java
@Entity
@Table(name = "t_order")
@SoftDelete(columnName = "deleted")
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Version
    @Column(nullable = false)
    private Integer version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();
}
```

注解名字全换了，没变的是「不写会怎样」。

`@GeneratedValue(strategy = GenerationType.IDENTITY)` 不写主键不走自增。JPA 默认策略 `AUTO`，Hibernate 按方言选，MySQL 上通常落到 `IDENTITY`；写死的代价是拿不到预分配 id，批量插入退化成一条条发。

`@Enumerated(EnumType.STRING)` 不写会出事：默认 `ORDINAL` **存序号**，`status` 列进的是 `0`、`1`、`2`，枚举中间插一个状态，历史数据语义全错位且无提示。

`@Version` 不写就是无保护的覆盖写，它还有个超出直觉的边界，后面讲。`@EntityListeners(AuditingEntityListener.class)` 配合 `@CreatedDate`、`@LastModifiedDate`，时间戳由框架回填。`@SoftDelete(columnName = "deleted")` 是**这一篇最重要的注解**，接管 `delete` 语义，后面单独一节讲。

关联是双向一对多：`@OneToMany(mappedBy = "order")` 表示订单是被动方，关联由 `OrderItem.order` 维护；`cascade = CascadeType.ALL` 让保存删除传导到明细；`orphanRemoval = true` 表示移出集合的明细当孤儿删掉。`fetch = FetchType.LAZY` 我显式写了，默认值就是 `LAZY`，后面讲抓取策略改的就是这一行。

`addItem` 我收成一个方法。

```java
public void addItem(OrderItem item) {
    item.setOrder(this);
    this.items.add(item);
}
```

`setOrder` 包级可见。双向关联必须**两边引用同时维护**：外部代码自己 `order.getItems().add(item)`，明细的 `order` 字段是 null，落库外键就是 null，不报错，查的时候才发现明细丢了。

### 审计字段，以及那个精度坑

JPA 不用写 `MetaObjectHandler`，启动类加一个开关。

```java
@SpringBootApplication
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaApplication {

    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS));
    }
}
```

`@EnableJpaAuditing` 是全局开关，带 `@CreatedDate` 的实体都走审计。

`DateTimeProvider` 必须有，这是我第一版漏掉后自己加回来的。`AuditingEntityListener` 默认取 `LocalDateTime.now()` 带完整纳秒，`created_at` 是 `DATETIME(3)` 只存毫秒。我第一版跑出来的原始数据：

```
"createdAt":"2026-09-18T18:39:45.4966677"
```

按 id 再查同一个订单：

```
"createdAt":"2026-09-18T18:39:45.497"
```

同一字段两个值，客户端拿创建时间戳做等值比较、算 `If-Modified-Since` 永远匹配不上。在源头截断到毫秒，加完两边一致：

```
"createdAt":"2026-09-18T19:01:11.128"
```

把列改成 `DATETIME(6)` 喂到微秒这条路我也想过，放弃了：差异只是从毫秒挪到微秒，两边取时间时机不同就对不上。根在「精度不一致」，不在「精度不够」。上一篇在 `MetaObjectHandler` 截断，这篇在 `DateTimeProvider` 截断，同一类问题换个框架会在另一个地方冒出来。

### 一对多和孤儿移除

`orders` 和 `order_items` 是两张表，不是第 2 篇那种一列 JSON，明细增删改单独处理。更新订单时我这样写：

```java
public void replaceItems(List<OrderItem> newItems) {
    this.items.clear();
    newItems.forEach(this::addItem);
}
```

`items` 是 `orphanRemoval = true` 的集合，`clear()` 后移出的明细在 flush 时被删。用「全清再加」不做「比对差异」，PUT 的语义就是整体替换。

本篇特有现象：实体有 `@SoftDelete`，**孤儿移除生成的是 UPDATE，不是 DELETE**。我整轮验证下来 delete 0 条、update 6 条，其中 3 条长这样：

```
update t_order_item set deleted=true where id=? and deleted=false
```

明细剔除、级联删除全变成软删除，第一次看到我还是愣了一下。

### 四种查询方式，以及什么时候用哪种

查数据的路数有几条，文档分散在各处，我放一起对比。

![](https://static.xiongneng.me/four-query-strategies-20260921213912.png)

**派生查询**，方法名就是查询：

```java
Optional<Order> findByOrderNo(String orderNo);

boolean existsByOrderNo(String orderNo);

List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

long countByStatus(OrderStatus status);
```

一行实现不用写，`By` 后跟条件，`OrderBy` 后跟排序，`And`、`Or`、`Like`、`GreaterThan` 都能拼。条件一多名字就长到读不下去，这时换下一种。

**JPQL** 写实体和属性名：

```java
@Query("select o from Order o where o.totalAmount >= :minAmount order by o.totalAmount desc")
List<Order> findNotCheaperThan(@Param("minAmount") BigDecimal minAmount);
```

`Order` 是**实体名**不是表名 `t_order`，`o.totalAmount` 是**属性名**不是列名 `total_amount`，和 MyBatis 原生 SQL 完全相反。

构造器表达式直接映射成 record：

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

两处容易写漏：`new` 后必须**全限定类名**，JPQL 不认识 import；参数顺序必须和 record 构造器一致，写反了运行期才抛。`coalesce(..., 0)` 兜底不能省：空集时 `sum()` 返回 null，record 参数是 `BigDecimal`，没兜底就是 NPE，只在「某个状态恰好为空」时出现。

**原生 SQL** 原样发给数据库：

```java
@Query(value = "select count(*) from t_order where status = :status and deleted = 0",
        nativeQuery = true)
long countActiveByStatus(@Param("status") String status);
```

SQL 里的 `deleted = 0` 是**手写的**，为什么留到软删除那节说。

**接口投影**适合列表页：

```java
public interface OrderSummary {
    Long getId();
    String getOrderNo();
    BigDecimal getTotalAmount();
    OrderStatus getStatus();
}
```

方法名对应实体 getter，返回值是框架代理实现，SQL 只 `select` 这四列：

```json
[{"id":1,"orderNo":"ORD2026091801","status":"CREATED","totalAmount":599.00},
 {"id":5,"orderNo":"ORD2026091805","status":"CREATED","totalAmount":45.90}]
```

`remark`、时间戳、明细集合在 SQL 层面就没查，列表页能少查一列是一列。

投影方法名和仓储重名时，返回类型不同不算重载，Java 编译不过，所以另开 `OrderSummaryRepository`，继承最朴素的 `Repository`：

```java
public interface OrderSummaryRepository extends Repository<Order, Long> {
    List<OrderSummary> findByStatus(OrderStatus status);
}
```

顺带的好处：这个仓储只有 `findByStatus`，没有 `save`、`delete`，接口上没有就是调不出来。

### Specification 组合查询

筛选条件可选时，JPQL 拼字符串不好看，`if` 分支一多也乱。Specification 把每个条件写成独立小函数，按需拼：

```java
public static Specification<Order> keywordContains(String keyword) {
    return (root, query, cb) -> {
        String pattern = "%" + keyword.toLowerCase() + "%";
        return cb.or(
                cb.like(cb.lower(root.get("orderNo")), pattern),
                cb.like(cb.lower(root.get("customerName")), pattern));
    };
}
```

`root.get("orderNo")` 里也是**实体属性名**。组合用 `Specification.allOf(...)`：

```java
public static Specification<Order> allOfNullable(Specification<Order>... specs) {
    return Specification.allOf(Arrays.stream(specs)
            .filter(Objects::nonNull)
            .toList());
}
```

`allOf` 为空时返回「永真」条件而不是 null，省掉调用处判空。配套还有 `anyOf(...)`、`unrestricted()`、`where(...)`、`not(...)` 和只产出谓词的 `PredicateSpecification`。

### 乐观锁，以及它管不到的那一半

字段上加 `@Version`：

```java
@Version
@Column(nullable = false)
private Integer version;
```

生成的 UPDATE 自带版本条件：

```
update t_order set customer_name=?, customer_phone=?, order_no=?, remark=?, status=?,
       total_amount=?, updated_at=?, version=? where id=? and version=?
```

`SET` 里是新值，`WHERE` 里是旧值。被抢先改过就匹配不上，影响 0 行，Hibernate 抛 `ObjectOptimisticLockingFailureException`，全局异常处理器翻译成 409。

问题在**保护范围**。`@Version` 比对的是持久化上下文里的快照版本，管「同一事务读出来又写回去」的并发；客户端传来的版本号隔着一次 HTTP 往返，Hibernate 不知道有这回事。直接 `setVersion` 塞进实体是错的，我试过：生成 UPDATE 用的是快照版本，塞进去的过期值反而**直接写进数据库**，版本号倒退。服务层显式比一次：

```java
private void checkVersion(Order order, Integer clientVersion) {
    if (clientVersion != null && !clientVersion.equals(order.getVersion())) {
        throw BusinessException.conflict(
                "订单已被其他人修改，请刷新后重试（当前版本 %d，提交版本 %d）"
                        .formatted(order.getVersion(), clientVersion));
    }
}
```

> `@Version` 保护的是同一个事务内的并发，跨 HTTP 往返的版本校验必须自己在服务层做。

`@Version` 还管同一请求里的「查一次、改几个字段、再更新」，中间被别的事务改过会拦下来。软删除这种框架内部写操作也带版本条件：

```
update t_order set deleted=true where id=? and deleted=false and version=?
```

`and version=?` 在，只是覆盖不到「跨请求」。

更新和状态流转之后我主动调一次 `flush()`：

```java
orderRepository.flush();
```

不手动 flush 异常照样抛，只是发生在方法返回后的事务提交时，堆栈指向代理，不好定位业务代码。

### 软删除，和它管不到的地方

软删除一个注解：

```java
@SoftDelete(columnName = "deleted")
```

`delete` 语义被接管，我整轮验证的结果是 **delete 0 条，update 6 条**，删除订单那条连参数是这样：

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

三处跟直觉不一样。`set deleted=true` 是布尔字面量，列类型建 `BIT(1)` 对应 `boolean`，`ddl-auto` 是 `validate`，对不上启动就失败。`and deleted=false` 是框架加的，只删没删过的；重复删第二次匹配不到行，影响 0 行，服务层据此返回 404。`and version=?` 在，`@Version` 对软删除同样生效。

删完我直接查了库：

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

`id=3` 还在，`deleted` 0 变 1，物理行数还是 6。查库还有个小坑：`BIT(1)` 直接 `SELECT deleted` 打出来是不可见二进制，看着像空列，容易误判「没删掉」，我上面查询写的是 `deleted+0`。

必须记住的规律：

> `@SoftDelete` 的过滤只加在 Hibernate 自己生成的语句上。原生 SQL 原样发给数据库，框架一个字符都不会改。

留了个原生 SQL 方法演示：

```java
@Query(value = "select count(*) from t_order where status = :status and deleted = 0",
        nativeQuery = true)
long countActiveByStatus(@Param("status") String status);
```

演示数据里 `id=3` 正好是 `SHIPPED`，刚被我删掉：

```http
GET /api/orders/count?status=SHIPPED
```

```json
{"status":"SHIPPED","count":0}
```

接口返回 `Map.of(...)`，键顺序在不同 JVM 启动之间会变，键换位置不是配置错了。返回 0 来自手写的 `deleted = 0`，去掉它被删订单会被算进来，**不报任何错**。这条和第 2 篇 `@TableLogic` 是同一类：框架的自动过滤只管它自己生成的 SQL。

![](https://static.xiongneng.me/soft-delete-flow-20260921213912.png)

### N+1，和两种抓取策略

这部分我建议你亲手跑一遍，数字很直观。`Order` 和 `OrderItem` 一对多，`fetch` 是 `LAZY`：分页查出 10 个订单，每个调一次 `order.getItems()`，Hibernate 就为每个订单补一条查明细的 SQL。这就是 N+1，事务里不报错，只是悄悄多发 N 条 SQL，流量上来变成慢查询。

默认配置下的 SQL 条数：

| 请求 | 默认配置 | `default_batch_fetch_size=50` |
|---|---|---|
| `GET /api/orders?size=1` | **3** | 3 |
| `GET /api/orders?size=3` | **5** | 3 |
| `GET /api/orders?size=5` | **7** | 3 |
| `GET /api/orders/1`（`@EntityGraph`） | 1 | 1 |

默认列是 `2 + 带明细的订单数`：count 一条、分页一条，每个带明细的订单各补一条。日志现场：

```
select ... from t_order o1_0 where o1_0.deleted=false order by o1_0.created_at desc limit ?, ?
select count(o1_0.id) from t_order o1_0 where o1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
select ... from t_order_item i1_0 where i1_0.order_id=? and i1_0.deleted=false
```

五条一样的 `where order_id=?`，一条一个值。加一行配置：

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 50
```

```
select ... from t_order_item i1_0
where i1_0.order_id in (?, ?, ..., ?) and i1_0.deleted=false
```

同一组请求再跑，`size=1`、`size=3`、`size=5` 全是 3 条，页大小不再影响 SQL 条数。挺有意思的是它没有把 `LAZY` 变 `EAGER`：集合还是懒加载，访问那一次才把同批集合一次抓回来，「不需要的明细不查」保住了。

单对象走第三条路：

```java
@EntityGraph(attributePaths = "items")
Optional<Order> findWithItemsById(Long id);
```

生成一条 join，一次就够：

```
select o1_0.id, ..., i1_0.product_name, ...
from t_order o1_0
left join t_order_item i1_0 on o1_0.id=i1_0.order_id and i1_0.deleted=false
where o1_0.id=? and o1_0.deleted=false
```

join 条件上的 `i1_0.deleted=false` 是软删除过滤，连 `on` 子句都加上了。

适用面：

| 场景 | 做法 | 结果 |
|---|---|---|
| 按主键查单个对象 | `@EntityGraph` | 1 条 SQL，join 抓取 |
| 分页或列表查多个对象 | `hibernate.default_batch_fetch_size` | 恒定少量 SQL，`in` 批量抓 |
| 不需要明细 | 接口投影或 DTO 查询 | 那一列不查，最省 |

**`@EntityGraph` 不要用在分页上**：`left join` 加 `limit`，一页 10 个订单带 30 条明细，SQL 返回 30 行，Hibernate 得在内存去重，`Page` 的 total 也可能错。列表场景用批量抓取或投影。

![](https://static.xiongneng.me/n-plus-one-20260921213912.png)

### 实体转 DTO 必须在事务里，因为 open-in-view 关了

回到前面埋的雷。`OrderResponse.from(order)` 要读 `order.getItems()`，懒加载集合，而 `open-in-view` 关了，没有「事务延伸到视图层」的兜底：

```java
public static OrderResponse from(Order order) {
    return new OrderResponse(
            order.getId(),
            order.getOrderNo(),
            ...,
            order.getItems().stream().map(OrderItemView::from).toList(),
            ...);
}
```

**实体转 DTO 必须在事务方法内完成**：放 Service 里，写方法带 `@Transactional`，读方法带 `@Transactional(readOnly = true)`。挪进 Controller 出了事务，读 `getItems()` 就抛 `LazyInitializationException`，接口 500。这个错我就是这么撞出来的，挪回去就好了。

`open-in-view` 开着时问题照样在，只是被藏起来，代价是每请求多占一个连接，上线后以「偶发慢查询」冒出来。关掉它让错在开发阶段抛出来，比在线上挖强。

`readOnly = true` 也不只表明意图：Spring 把提示传给 JDBC 连接，Hibernate 收到后跳过脏检查、不保留快照，批量读省内存和 CPU。

## 完整案例，跑起来看

建库还是一条命令。

```bash
mysql -h 127.0.0.1 -P 3306 -uroot -p < src/main/resources/sql/schema.sql
```

启动，创建订单：

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13800138000","totalAmount":299.50,"remark":"演示下单",
       "items":[{"productName":"机械键盘","price":299.50,"quantity":1},
                {"productName":"腕托","price":25.00,"quantity":2}]}'
```

![](https://static.xiongneng.me/jpa-order-created-201-20260918190113.png)

```json
{"id":6,"orderNo":"ORD202609181901110307456","customerName":"熊大","customerPhone":"138****8000",
 "totalAmount":299.50,"status":"CREATED","statusLabel":"已创建","remark":"演示下单",
 "items":[{"id":6,"productName":"机械键盘","price":299.50,"quantity":1,"amount":299.50},
          {"id":7,"productName":"腕托","price":25.00,"quantity":2,"amount":50.00}],
 "version":0,"createdAt":"2026-09-18T19:01:11.128","updatedAt":"2026-09-18T19:01:11.128"}
```

`orderNo`、`status`、时间戳、`version` 都是服务端填的；一次请求一条主表 `INSERT`，靠 `CascadeType.ALL` 带上两条明细。

乐观锁我分两步测：

```bash
# 第一次，带上当前版本号 0，200，版本号变成 1
curl -i -X PUT http://localhost:8080/api/orders/6 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":888.00,
       "remark":"改了金额","items":[{"productName":"机械键盘","price":888.00,"quantity":1}],
       "version":0}'

# 第二次，还拿版本号 0，这个已经过期了，409
curl -i -X PUT http://localhost:8080/api/orders/6 \
  -H "Content-Type: application/json" \
  -d '{"customerName":"熊大","customerPhone":"13900139000","totalAmount":666.00,
       "remark":"并发写入","version":0}'
```

![](https://static.xiongneng.me/jpa-optimistic-lock-409-20260918190114.png)

```json
{
  "detail": "订单已被其他人修改，请刷新后重试（当前版本 1，提交版本 0）",
  "instance": "/api/orders/6",
  "status": 409,
  "title": "业务规则不满足",
  "type": "urn:problem-type:business-rule-violation"
}
```

409 是**服务层拦的**，UPDATE 没发出去：跨请求的版本校验只能自己做。

状态流转：

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

聚合统计：

```bash
curl -s http://localhost:8080/api/orders/stats
```

```json
[{"status":"COMPLETED","count":1,"totalAmount":1288.00,"statusLabel":"已完成"},
 {"status":"CREATED","count":2,"totalAmount":644.90,"statusLabel":"已创建"},
 {"status":"PAID","count":2,"totalAmount":1017.00,"statusLabel":"已支付"},
 {"status":"SHIPPED","count":1,"totalAmount":88.50,"statusLabel":"已发货"}]
```

响应体都是我从真实调用里抄下来的。浏览器直接打开接口：

![](https://static.xiongneng.me/jpa-browser-json-view-20260918190115.png)

Chrome 对 `application/json` 套一层自己的查看器，默认挤一行，点左上角「美观输出」才展开；开关在浏览器 UI 层，不在 DOM 里，脚本点不了，所以截图是单行渲染。

## 这一篇的测试怎么写

测试两个类，分工不同。

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderRepositorySliceTest { ... }
```

切片测试只加载 JPA 自动配置，不起 Web 容器、不扫 Service 和 Controller，测仓储跑得快、定位准。import 包名就是前面表里的新包名。`@AutoConfigureTestDatabase(replace = Replace.NONE)` 必须写：切片测试默认换内存库，测试要跑真实 MySQL，项目里也没引 H2，不写会报「找不到可用的嵌入式数据库」。

全链路那个：

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OrderApiIntegrationTest { ... }
```

26 个用例走完整 Web 层加事务：参数校验、事务边界、懒加载在视图层炸不炸、问题详情结构。

懒加载那条看着重复，守的是一道线：

```java
@Test
@DisplayName("查询订单：明细在事务内被转成 DTO，open-in-view 关掉也不炸")
void getByIdReturnsItemsDespiteOpenInViewDisabled() {
    OrderResponse created = createOrder(uniqueTag());

    MvcTestResult result = mockMvc.get().uri("/api/orders/{id}", created.id()).exchange();
    result.assertThat().hasStatusOk();

    OrderResponse reloaded = jsonMapper.readValue(bodyText(result), OrderResponse.class);
    assertThat(reloaded.items()).hasSize(2);
}
```

哪天有人把 `OrderResponse.from(order)` 挪进 Controller，这条立刻红；没有它，这个改动在开发环境可能看不出来，直到线上某个请求才炸。

两个类都真连 MySQL，不加 H2 是我刻意的：软删除、乐观锁、`open-in-view` 关闭后的行为都是数据库和事务层面的事，内存库是「测了个仿制品」。依赖外部数据库的代价用两道防护抵掉：`application-test.yml` 让 Hikari 懒加载，上下文创建阶段不碰库；用例执行前再探测，连不上整体 `skip`。

```yaml
spring:
  datasource:
    hikari:
      initialization-fail-timeout: -1
      connection-timeout: 3000
```

没配 MySQL 的机器上跑 `mvn test`，看到的是 `Skipped` 不是红。类上 `@Transactional` 每用例回滚，`@SoftDelete` 的标记也一起回滚，用例互不污染。

最后，`mvn test` 的结果：

```
Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

38 个用例全绿。

## 避坑指南

**坑一，Hibernate 7 的 SQL 日志没有 `Hibernate: ` 前缀。** 最坑的一条，坑的是前提。SQL 走 `org.hibernate.SQL` logger：

```
2026-09-18T19:01:11.098+08:00 DEBUG 4144 --- [springboot4-jpa] [nio-8080-exec-4] org.hibernate.SQL   :
    select
        o1_0.id, ...
    from t_order o1_0
```

**没有前缀。** `grep "Hibernate:"` 数出来恒等于 0，接着得出「一条 SQL 都没发」的荒谬结论。参数格式是 `binding parameter`：

```
binding parameter (1:BIGINT) <- [3]
```

开 `format_sql` 后一条语句跨多行，按行 grep 会切碎语句。我按日志行首时间戳把语句切块还原，工具不复杂，几十行 Python 就够了。

**坑二，`@Validated` 类级别注解，第 1 篇的坑继续有效。** 校验改由 AOP 代理处理，抛 `ConstraintViolationException` 而不是 `HandlerMethodValidationException`，全局异常处理器接不到，结果是 500 不是 400。不写就对了。

**坑三，JPA 自动配置拆成三个模块，写旧包名编译不过。** `org.springframework.boot.autoconfigure.orm.jpa.*` 直接过不去，import 跟着模块表走。

**坑四，测试注解包名每篇都不一样。** 这篇三个注解三个包，第 1 篇的 `@AutoConfigureMockMvc` 又是第四个包，**每篇都得重新查一遍**。

**坑五，`spring.jpa.open-in-view` 默认还是 `true`，`defer-datasource-initialization` 元数据查不到但还在。** 后者从 `Environment` 直读。判断配置项还在不在，别只看元数据。

**坑六，`@Enumerated` 默认 `ORDINAL` 存序号。** 必须 `@Enumerated(EnumType.STRING)`，不写不报错，枚举调整时历史数据语义全错位。

**坑七，`sum()` 空集返回 null，不是 0。** 映射到 `BigDecimal` 构造器参数就是 NPE，套 `coalesce(..., 0)`。

**坑八，JPQL 构造器表达式参数顺序写反编译期不报错。** `select new com.xxx.OrderStatusStat(...)` 没有类型检查，运行期才抛；类名必须全限定。

**坑九，`Sort` 收实体属性名，传数据库列名会抛异常。** MyBatis-Plus 的 `OrderItem` 拼 SQL 片段写 `created_at`，Spring Data 的 `Sort` 走属性路径写 `createdAt`，上一篇的 `sort=created_at,desc` 拿过来直接抛：

```
PropertyReferenceException: No property 'created' found for type 'Order'; Did you mean 'createdAt'?
```

报错说的是 `created` 不是 `created_at`：**下划线是嵌套属性分隔符**，字符串被拆成「`created` 下的 `at`」。处理分两层：`OrderPageQuery` 维护可排序字段白名单，不在就回退默认字段；方向写错报 422，字段错是笔误可以兜底，方向错是静默错误。

**坑十，`@Version` 管不到跨 HTTP 往返的版本校验。** 手动 `setVersion` 不触发检查，反而把过期值写进库。

**坑十一，时间字段精度，第 2 篇踩过这篇又踩一次。** `LocalDateTime.now()` 带纳秒，`DATETIME(3)` 只存毫秒；截断位置从 `MetaObjectHandler` 换到 `DateTimeProvider`，道理没变。

**坑十二，`@EntityGraph` 不要用在分页上。** `left join` 加 `limit` 放大结果集，分页和 total 都可能错；列表用 `default_batch_fetch_size` 或投影。

**坑十三，`@Where` 写了编译不过，Hibernate 7 没有这个注解。** 过滤 SQL 用 `@SQLRestriction`；软删除直接 `@SoftDelete`。

**坑十四，`BIT(1)` 列在命令行里看着像空的。** `SELECT deleted` 打不出可见内容：二进制类型，mysql 客户端输出原始字节，改 `SELECT deleted+0` 显示 0 和 1。验证手段的坑，照样能让人白折腾半小时。

## 小结

这篇坑多的原因我自己的总结是：JPA 这条链上有三套元数据同时起作用，默认值和覆盖范围各不相同。选型上如果你要分层清楚、每层能单独换，JPA 这条路值得走，代价是得知道每件事归哪层管，跟 MyBatis-Plus「一个框架全包」的省心是两种东西。工程在 `springboot4-jpa`，测试 38 个全绿，27 个场景的 curl 实录在 README，SQL 日志和语句清单都是真实抓的。

4.1.1 上的关键事实，留一张表备查：

| 主题 | 4.1.1 的现状 |
|---|---|
| 自动配置 | 拆在 `spring-boot-jpa` / `-hibernate` / `-data-jpa` 三个模块里 |
| 测试注解 | 散在 `data.jpa.test` / `jpa.test` / `jdbc.test` 三个包 |
| 测试 starter | `spring-boot-starter-data-jpa-test`，4.1.1 新增 |
| 软删除 | `@SoftDelete` 接管 `delete`；`@Where` 不存在，过滤注解是 `@SQLRestriction` |
| SQL 日志 | `org.hibernate.SQL` logger，行首无前缀 |
| 参数日志 | `binding parameter (N:TYPE) <- [value]` |
| 审计时间 | 截断到毫秒，与 `DATETIME(3)` 对齐 |
| `open-in-view` | 默认 `true`，要自己关 |

还没解决的事有两条。`@SoftDelete` 的过滤只管框架生成的 SQL，原生 SQL 自己写条件，和第 2 篇 `@TableLogic` 同一个规律，目前只能在代码评审里盯着；`@Version` 只保护同一事务内的并发，跨 HTTP 的版本校验必须在服务层自己做，我还没找到能让框架接管的干净写法，先用 `checkVersion` 顶着。

## 参考链接

- [Spring Data JPA 官方参考文档](https://docs.spring.io/spring-data/jpa/reference/)
- [Spring Boot 4.1 Reference - JPA](https://docs.spring.io/spring-boot/4.1/reference/data/sql.html#data.sql.jpa-and-spring-data)
- [Hibernate ORM 7.0 迁移指南](https://docs.jboss.org/hibernate/orm/7.0/migration-guide/migration-guide.html)
- [Jakarta Persistence 3.2 规范](https://jakarta.ee/specifications/persistence/3.2/)
