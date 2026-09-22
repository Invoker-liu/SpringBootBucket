package com.xncoding.multisource.mapper.pos;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xncoding.multisource.domain.Order;
import com.xncoding.multisource.dto.OrderDetail;

import org.apache.ibatis.annotations.Param;

/**
 * 交易库的订单 mapper。
 *
 * <p>这个包被 {@code PosDataSourceConfig} 用
 * {@code @MapperScan(basePackages = "…mapper.pos", sqlSessionFactoryRef = "posSqlSessionFactory")}
 * 单独扫走。多数据源下 mapper 必须按包拆开，否则没法告诉扫描器哪个接口该绑哪个
 * {@code SqlSessionFactory}，结果是要么全部绑到同一个、要么直接报
 * 「Invalid bound statement」。
 */
public interface OrderMapper extends BaseMapper<Order> {

    /** join t_order 与 t_order_item，返回聚合视图。 */
    OrderDetail selectOrderDetail(@Param("orderId") Long orderId);

    /** 统计订单总数，用来验证事务回滚有没有生效。 */
    int countAll();

    /**
     * 这个 mapper 连的到底是哪个库。
     *
     * <p>静态方案也需要自证 —— 两台服务器、两套配置，很容易出现
     * 「mapper 包名写错一个字母，两个工厂扫到了同一批接口」这种情形，
     * 那时候代码照样能跑，只是两个库查出来是同一份数据。
     * 让每个 mapper 自己报一次 {@code DATABASE()}，比人工核对配置可靠。
     */
    String currentDatabase();
}
