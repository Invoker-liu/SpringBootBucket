package com.xncoding.transaction;

import com.xncoding.transaction.domain.TxEvent;
import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.service.IsolationService;
import com.xncoding.transaction.service.ReadOnlyService;
import com.xncoding.transaction.service.TransactionEventService;
import com.xncoding.transaction.support.ScenarioRunner;
import com.xncoding.transaction.support.TransactionManagerInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.List;

import static com.xncoding.transaction.TestSupport.assertTotalUnchanged;
import static com.xncoding.transaction.TestSupport.balance;
import static com.xncoding.transaction.TestSupport.thrownException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事务基础设施本身。
 *
 * <p>这一组不测业务，测的是「Boot 到底给了我什么」——
 * 因为本篇一半的结论都是关于「什么东西是自动配置白送的、它叫什么名字、它什么时候才解析」。
 * 这些东西平时没人会去确认，出问题的时候又全靠它们定位。
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionInfrastructureTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private TransactionManagerInspector inspector;
    @Autowired
    private ReadOnlyService readOnlyService;
    @Autowired
    private IsolationService isolationService;
    @Autowired
    private TransactionEventService eventService;
    @Autowired
    private ScenarioRunner runner;

    // ------------------------------------------------------------ 容器事实

    @Test
    @DisplayName("容器里只有一个事务管理器，叫 transactionManager，类型是 JdbcTransactionManager")
    void singleTransactionManager() {
        assertThat(inspector.count()).isEqualTo(1);
        assertThat(context.getBeanNamesForType(PlatformTransactionManager.class))
                .containsExactly("transactionManager");
        assertThat(context.getBean(PlatformTransactionManager.class))
                .as("Boot 4 默认给的是 JdbcTransactionManager，不是 DataSourceTransactionManager —— "
                        + "它多了一层 SQLException → DataAccessException 的翻译")
                .isInstanceOf(JdbcTransactionManager.class);
    }

    @Test
    @DisplayName("TransactionTemplate 是自动配置白送的")
    void transactionTemplateIsAutoConfigured() {
        // TransactionTemplateConfiguration 的生效条件是
        // @ConditionalOnSingleCandidate(PlatformTransactionManager)，
        // 所以「能注入它」本身就等价于「事务管理器唯一」
        assertThat(context.getBean(TransactionTemplate.class)).isNotNull();
    }

    /**
     * 这条解释了「事务管理器名字写错为什么到调用时才炸」。
     *
     * <p>{@code ProxyTransactionManagementConfiguration} 只在
     * {@code txManager} 字段非空时才 {@code interceptor.setTransactionManager(...)} ——
     * 而那个字段只有实现了 {@code TransactionManagementConfigurer} 才会被填。
     * 默认情况下它是 null，所以拦截器每次都到调用现场才去容器里找事务管理器。
     */
    @Test
    @DisplayName("事务拦截器不预先绑定事务管理器：事务管理器是每次调用时才解析的")
    void transactionInterceptorResolvesManagerLazily() {
        TransactionInterceptor interceptor = context.getBean(TransactionInterceptor.class);

        assertThat(interceptor.getTransactionManager())
                .as("默认没有绑定：所以名字写错不会在启动时暴露，只会在第一次调用时报错")
                .isNull();
    }

    @Test
    @DisplayName("事务通知器的 order 是 Integer.MAX_VALUE（@EnableTransactionManagement 的默认值）")
    void advisorOrderIsLowestPrecedence() {
        BeanFactoryTransactionAttributeSourceAdvisor advisor =
                context.getBean(BeanFactoryTransactionAttributeSourceAdvisor.class);

        assertThat(((Ordered) advisor).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    // ------------------------------------------------------------ 两个纠错点

    /**
     * 直接把「rollbackFor 的默认值是空数组」变成断言。
     *
     * <p>参考文献里写的「默认是 Throwable 的子类」是错的，这条断言就是证据。
     */
    @Test
    @DisplayName("纠错：@Transactional.rollbackFor 的默认值是空数组，不是「Throwable 的子类」")
    void rollbackForDefaultIsEmptyArray() throws Exception {
        Method rollbackFor = Transactional.class.getMethod("rollbackFor");

        assertThat(rollbackFor.getDefaultValue()).isInstanceOf(Class[].class);
        assertThat((Class<?>[]) rollbackFor.getDefaultValue()).isEmpty();
    }

    @Test
    @DisplayName("纠错：默认只回滚 RuntimeException 和 Error，受检异常一律提交")
    void defaultRollbackRule() {
        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute();

        assertThat(attribute.rollbackOn(new RuntimeException("运行时异常"))).isTrue();
        assertThat(attribute.rollbackOn(new Error("错误"))).isTrue();
        assertThat(attribute.rollbackOn(new Exception("受检异常")))
                .as("这就是「接口说失败、数据说成功」的根因")
                .isFalse();
    }

    // ------------------------------------------------------------ 只读事务

    @Test
    @DisplayName("只读事务：Spring 和数据库会话两边都变成了只读")
    void readOnlyIsPropagatedToConnection() {
        ScenarioResult result = runner.record("t", "e", "c", steps -> readOnlyService.readOnlyRead(steps));

        assertThat(result.steps().get(0).detail())
                .as("两层都要看：Spring 记了标记不算数，连接上也得真的变")
                .contains("Spring 声明=true")
                .contains("数据库会话=只读");
    }

    @Test
    @DisplayName("只读事务里写入：被 JDBC 驱动客户端拦下，抛 TransientDataAccessResourceException 并回滚")
    void readOnlyBlocksWrite() {
        ScenarioResult result = runner.record("t", "e", "c", steps -> readOnlyService.readOnlyWrite(steps));

        assertThat(thrownException(result))
                .as("拦截发生在 MySQL Connector/J 的 StatementImpl 里，不是 MySQL 服务端")
                .isEqualTo("TransientDataAccessResourceException");
        assertThat(result.steps().get(0).detail()).contains("数据库会话=只读");
        assertThat(balance(result, "熊大"))
                .as("异常是 RuntimeException，所以事务顺带回滚了")
                .isEqualByComparingTo("1000.00");
        assertTotalUnchanged(result);
    }

    // ------------------------------------------------------------ 隔离级别

    @Test
    @DisplayName("隔离级别：DEFAULT 什么都不设，其余三种真的落到连接上")
    void isolationLevels() {
        ScenarioResult result = runner.record("t", "e", "c", steps -> {
            isolationService.defaultLevel(steps);
            isolationService.readCommitted(steps);
            isolationService.repeatableRead(steps);
            isolationService.serializable(steps);
        });

        List<StepResult> steps = result.steps();
        assertThat(steps).hasSize(4);

        assertThat(steps.get(0).detail())
                .as("ISOLATION_DEFAULT 的真实语义是「不改连接」，读到的 MySQL 默认值是 REPEATABLE-READ")
                .contains("Spring 声明=null")
                .contains("REPEATABLE-READ");
        assertThat(steps.get(1).detail()).contains("Spring 声明=READ_COMMITTED").contains("READ-COMMITTED");
        assertThat(steps.get(2).detail()).contains("Spring 声明=REPEATABLE_READ").contains("REPEATABLE-READ");
        assertThat(steps.get(3).detail()).contains("Spring 声明=SERIALIZABLE").contains("SERIALIZABLE");
    }

    // ------------------------------------------------------------ 事务事件四个相位

    @Test
    @DisplayName("事务提交：四个相位里该触发的都触发，BEFORE_COMMIT 那条留下了")
    void eventsOnCommit() {
        eventService.clearEvents();

        runner.record("t", "e", "c", steps -> eventService.publishAndCommit(steps));

        assertThat(eventService.events()).extracting(TxEvent::getPhase)
                .as("BEFORE_COMMIT 和业务数据在同一条事务里，业务提交它就留下")
                .contains("BEFORE_COMMIT")
                .contains("AFTER_COMPLETION")
                .doesNotContain("AFTER_ROLLBACK");
    }

    /**
     * 这一节唯一的核心证据。
     *
     * <p>{@code BEFORE_COMMIT} 的监听器确实执行了、SQL 确实发出去了，
     * 但它的写入在业务事务里，业务一回滚它就没了。
     * 这也是「不要在 BEFORE_COMMIT 里发短信、调第三方接口」的原因。
     */
    @Test
    @DisplayName("事务回滚：BEFORE_COMMIT 那条凭空消失，只剩回滚相关的两个相位")
    void eventsOnRollback() {
        eventService.clearEvents();

        runner.record("t", "e", "c", steps -> {
            try {
                eventService.publishAndRollback(steps);
            } catch (Exception ignored) {
                // 这个异常是故意抛的
            }
        });

        List<String> phases = eventService.events().stream().map(TxEvent::getPhase).toList();
        assertThat(phases)
                .as("监听器执行过不代表数据留得下")
                .contains("AFTER_ROLLBACK")
                .contains("AFTER_COMPLETION")
                .doesNotContain("BEFORE_COMMIT");
    }

    @Test
    @DisplayName("没有事务时发布事件：只有开了 fallbackExecution 的监听器响应")
    void eventsWithoutTransaction() {
        eventService.clearEvents();

        runner.record("t", "e", "c", steps -> eventService.publishWithoutTransaction(steps));

        assertThat(eventService.events())
                .as("默认 fallbackExecution = false，没事务就当没听见")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getPhase()).contains("无事务时也执行"));
    }
}
