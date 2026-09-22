package com.xncoding.jpa.repository;

import com.xncoding.jpa.domain.Order;
import com.xncoding.jpa.domain.OrderStatus;
import com.xncoding.jpa.dto.OrderStatusStat;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储。
 * <p>
 * 继承 {@link JpaRepository} 拿到 CRUD 与分页，再继承 {@link JpaSpecificationExecutor}
 * 拿到动态条件查询。两个接口都能直接拿到 {@code findAll(Pageable)} 之类的现成方法，
 * 一行实现都不用写。
 * <p>
 * 这里把 Spring Data JPA 的四种查询方式各放了一个例子，正文里逐个对照。
 */
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    // ------------------------------------------------------------------
    // 方式一：派生查询。方法名本身就是查询，不用写一行实现。
    // ------------------------------------------------------------------

    Optional<Order> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    /** 方法名里的 OrderBy 同样走实体属性名 */
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    long countByStatus(OrderStatus status);

    /**
     * 带关联抓取的单条查询。
     * <p>
     * {@code @EntityGraph(attributePaths = "items")} 让这一条 SQL 直接把 {items}
     * 用 join 抓出来，而不是等访问 {@code order.getItems()} 时再补一条查询。
     * 这是解决单对象 N+1 最省事的办法——不用改 JPQL，加个注解就行。
     * <p>
     * 注意方法名里的 {@code WithItems} 不参与解析：Spring Data 只认
     * {@code find...By} 之间的部分做投影判定，这里当装饰性文字处理，
     * 真正的条件只有 {@code ById}。
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(Long id);

    // ------------------------------------------------------------------
    // 方式二：JPQL。写的是实体和属性名，不是表名和列名。
    // ------------------------------------------------------------------

    @Query("select o from Order o where o.totalAmount >= :minAmount order by o.totalAmount desc")
    List<Order> findNotCheaperThan(@Param("minAmount") BigDecimal minAmount);

    /**
     * 按状态聚合。
     * <p>
     * 返回的是 record，靠 JPQL 的构造器表达式 {@code new 全限定类名(...)} 映射。
     * {@code sum()} 在没有任何行时会返回 null，所以外面套一层 {@code coalesce}，
     * 否则某个状态一条数据都没有时，聚合结果直接是 null 而不是 0。
     */
    @Query("""
            select new com.xncoding.jpa.dto.OrderStatusStat(
                       o.status, count(o), coalesce(sum(o.totalAmount), 0))
            from Order o
            group by o.status
            order by o.status
            """)
    List<OrderStatusStat> statByStatus();

    // ------------------------------------------------------------------
    // 方式三：原生 SQL。写的是数据库表名和列名。
    // ------------------------------------------------------------------

    /**
     * 统计某状态下的有效订单数。
     * <p>
     * <b>这个方法的重点不在统计，在于那个手写的 {@code deleted = 0}。</b>
     * 实体上标了 {@code @SoftDelete}，Hibernate 会给它自己生成的所有查询自动补上
     * 软删除过滤条件，但原生 SQL 是"原样发给数据库"，框架一个字符都不会改。
     * 忘了写，被删掉的订单就会被算进来，而且不报任何错。
     * <p>
     * 这里传的是状态名字符串而不是枚举：原生 SQL 的参数绑定走 JDBC，
     * 枚举得靠 Hibernate 的类型映射转换，直接在 SQL 里写 {@code status = 'PAID'}
     * 更省事，但要自己保证字符串和枚举名一致。
     */
    @Query(value = "select count(*) from t_order where status = :status and deleted = 0",
            nativeQuery = true)
    long countActiveByStatus(@Param("status") String status);
}
