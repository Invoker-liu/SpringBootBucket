package com.xncoding.multisource;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.xncoding.multisource.domain.Order;
import com.xncoding.multisource.dto.DualDatabaseSnapshot;
import com.xncoding.multisource.dto.OrderDetail;
import com.xncoding.multisource.mapper.pos.OrderMapper;
import com.xncoding.multisource.service.BizProductService;
import com.xncoding.multisource.service.PosOrderService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 方案 A（静态多数据源）的验证。
 *
 * <p>这里唯一一个「和别的文章不一样」的用例是
 * {@link #wrongTransactionManagerLeavesDataBehind()} ——
 * 事务管理器绑错数据源时，异常照样抛、事务照样回滚，
 * 但数据留在库里。这个 bug 不报错、不打 WARN，
 * 只有把「调用前后的行数」摆出来才看得见。
 */
@SpringBootTest
@ActiveProfiles("test")
class StaticDataSourceTest {

    /** 测试自己造的数据统一用这个前缀，跑完自己删掉，不污染演示数据 */
    private static final String TEST_ORDER_PREFIX = "TEST-MS-";

    @Autowired
    private PosOrderService posOrderService;

    @Autowired
    private BizProductService bizProductService;

    @Autowired
    private OrderMapper orderMapper;

    @AfterEach
    void cleanUpTestOrders() {
        orderMapper.delete(Wrappers.<Order>lambdaQuery()
                .likeRight(Order::getOrderNo, TEST_ORDER_PREFIX));
    }

    @Test
    @DisplayName("两个 mapper 各自连着自己的库")
    void eachMapperIsBoundToItsOwnDatabase() {
        assertThat(posOrderService.currentDatabase()).isEqualTo("springboot4_pos");
        assertThat(bizProductService.currentDatabase()).isEqualTo("springboot4_biz");
    }

    @Test
    @DisplayName("一次请求读两个库")
    void readsTwoDatabasesInOneRequest() {
        DualDatabaseSnapshot snapshot = new DualDatabaseSnapshot(
                posOrderService.currentDatabase(),
                posOrderService.count(),
                bizProductService.currentDatabase(),
                bizProductService.count());

        assertThat(snapshot.posDatabase()).isEqualTo("springboot4_pos");
        assertThat(snapshot.bizDatabase()).isEqualTo("springboot4_biz");
        assertThat(snapshot.orderCount()).isGreaterThanOrEqualTo(3);
        assertThat(snapshot.productCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("交易库里的 join 查得出来，说明两张表确实在同一个库")
    void joinsTablesInsideTransactionDatabase() {
        OrderDetail detail = posOrderService.detail(1L);

        assertThat(detail.getOrderNo()).isEqualTo("POS20260901001");
        assertThat(detail.getDatabaseName()).isEqualTo("springboot4_pos");
        assertThat(detail.getItems()).hasSize(1);
        assertThat(detail.getItems().get(0).getProductName()).isEqualTo("机械键盘");
    }

    @Test
    @DisplayName("运营库的商品查询走的是另一个库")
    void queriesProductsFromOperationDatabase() {
        assertThat(bizProductService.lowStock(50))
                .extracting("productName")
                .containsExactlyInAnyOrder("显示器", "人体工学椅");
    }

    @Test
    @DisplayName("事务管理器与写入目标一致时，异常触发回滚")
    void correctTransactionManagerRollsBack() {
        int before = posOrderService.count();

        assertThatThrownBy(() -> posOrderService.insertThenFailCorrectly(TEST_ORDER_PREFIX + "OK"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(posOrderService.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("事务管理器绑错库时，异常抛了、事务回滚了，数据却留下了")
    void wrongTransactionManagerLeavesDataBehind() {
        int before = posOrderService.count();

        assertThatThrownBy(() -> posOrderService.insertThenFailWithWrongManager(TEST_ORDER_PREFIX + "WRONG"))
                .isInstanceOf(IllegalStateException.class);

        // 这一行是整个方案 A 里最值得记住的一条
        assertThat(posOrderService.count())
                .as("事务回滚的是运营库上那个空事务，交易库这一笔不在其中，所以留了下来")
                .isEqualTo(before + 1);
    }
}
