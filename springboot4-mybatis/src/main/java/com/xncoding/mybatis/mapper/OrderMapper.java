package com.xncoding.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xncoding.mybatis.domain.Order;
import com.xncoding.mybatis.dto.OrderStatusStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单 Mapper。
 * <p>
 * 继承 {@code BaseMapper<Order>} 之后，单表增删改查一个方法都不用写：
 * insert / deleteById / updateById / selectById / selectList / selectPage / selectCount /
 * exists / selectMaps / selectObjs 以及批量 insert 全都现成。
 * <p>
 * 只有框架猜不出来的查询才需要自己声明，实现在 {@code resources/mapper/OrderMapper.xml}。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 查回收站：已被逻辑删除的订单。
     * <p>
     * 这是自定义 SQL 相对框架生成 SQL 的一个关键差别——{@code @TableLogic} 只会给
     * MyBatis-Plus 自己生成的语句追加 {@code deleted = 0}，手写的 XML 不会。
     * 所以这里能查到 {@code deleted = 1} 的数据。
     *
     * @param limit 最多返回多少条
     */
    List<Order> selectDeleted(@Param("limit") int limit);

    /**
     * 按状态聚合：每个状态的订单数与金额合计。
     * <p>
     * 这种 {@code GROUP BY} 加聚合函数的查询，条件构造器写不出来，老老实实写 SQL。
     */
    List<OrderStatusStat> selectStatusStats();
}
