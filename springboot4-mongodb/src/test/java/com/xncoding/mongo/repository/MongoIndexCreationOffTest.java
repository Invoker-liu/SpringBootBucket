package com.xncoding.mongo.repository;

import com.xncoding.mongo.domain.Order;
import com.xncoding.mongo.domain.OrderItem;
import com.xncoding.mongo.domain.OrderStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 索引自动创建的<b>反面对照</b>：把开关关掉时，{@code @Indexed} 等于没写。
 *
 * <h2>为什么单独一个类</h2>
 * 因为 `auto-index-creation` 是<b>上下文启动期</b>读取的属性，同一个 Spring 上下文里
 * 没法一半用例打开、一半关掉。只能单开一个类，用 {@code properties} 把开关压成 false。
 * <p>
 * 另一个必须独立的理由：这个类要<b>删集合</b>来做干净验证，
 * 而索引是在第一次写文档时按映射创建的。如果和其它仓储测试共用一个库，
 * 谁先跑、谁把集合删了，都会让结果飘。所以这里单独换一个库
 * （{@code springboot4_mongo_indexdemo}），互不干扰。
 * <p>
 * 和 {@code OrderRepositorySliceTest#indexesAreCreatedWhenAutoIndexCreationEnabled}
 * 合起来看，两个用例就是同一件事的两面：
 * <b>开关是 true 时三个索引都在，是 false 时一个都没有。</b>
 * 而 {@code spring.data.mongodb.auto-index-creation} 的默认值就是 false。
 */
@DataMongoTest(properties = {
        "spring.mongodb.uri=mongodb://${MONGO_USER:root}:${MONGO_PASSWORD:root123456}@"
                + "${MONGO_HOST:127.0.0.1}:${MONGO_PORT:27017}/springboot4_mongo_indexdemo?authSource=admin",
        "spring.data.mongodb.auto-index-creation=false"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoIndexCreationOffTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeAll
    void checkDatabase() {
        try {
            mongoTemplate.executeCommand("{ ping: 1 }");
        } catch (Exception ex) {
            Assumptions.assumeTrue(false,
                    "MongoDB 不可用（" + ex.getMessage() + "），跳过索引开关测试");
        }
    }

    @Test
    @DisplayName("关闭 auto-index-creation：写文档正常，但三个索引一个都没建出来")
    void indexesAreNotCreatedWhenAutoIndexCreationOff() {
        // 从头开始：把集合删掉，连上次跑剩下的索引一起清掉
        mongoTemplate.dropCollection(Order.class);

        Order order = new Order("ORD_INDEX_OFF_" + System.nanoTime(), "索引开关",
                "13800138000", new BigDecimal("299.50"), "关掉索引自动创建");
        order.addItem(new OrderItem("机械键盘", new BigDecimal("299.50"), 1));
        mongoTemplate.save(order);

        // 文档写得进去 —— 这一点很关键：关掉开关之后，一切"看起来"都正常
        // 注意 getCollection() 只接受集合名，没有 Class 重载；MongoTemplate 里
        // 由实体推集合名的方法是 getCollectionName(Class)
        assertThat(mongoTemplate.getCollection(mongoTemplate.getCollectionName(Order.class))
                .countDocuments()).isEqualTo(1);

        List<String> indexNames = mongoTemplate.indexOps(Order.class).getIndexInfo().stream()
                .map(IndexInfo::getName)
                .toList();

        // 集合是刚刚才建出来的，除了 MongoDB 自带的 _id 索引，什么都不该有
        assertThat(indexNames)
                .as("关掉开关后，@Indexed / @CompoundIndex / @Indexed(unique) 全部只是注释")
                .doesNotContain("uk_order_no", "idx_status", "idx_status_created_at");

        // 连带后果：唯一约束不存在，同一个订单号可以重复插入。
        // 这是整件事最危险的地方 —— 代码看着有 @Indexed(unique = true)，
        // 业务上却没有唯一性保证，而且没有任何地方会报错。
        mongoTemplate.save(new Order(order.getOrderNo(), "重复单号", "13900139000",
                new BigDecimal("1.00"), "居然插进去了"));
        assertThat(mongoTemplate.getCollection(mongoTemplate.getCollectionName(Order.class))
                .countDocuments())
                .as("唯一索引不存在，重复订单号被成功写入")
                .isEqualTo(2);

        // 收尾：把演示库清干净，别留给下一次运行
        mongoTemplate.dropCollection(Order.class);
    }
}
