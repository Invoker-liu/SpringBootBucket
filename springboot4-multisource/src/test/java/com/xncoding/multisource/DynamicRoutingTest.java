package com.xncoding.multisource;

import com.xncoding.multisource.context.DataSourceKey;
import com.xncoding.multisource.dto.DataSourceProbeResult;
import com.xncoding.multisource.dto.ProbeScenarioResult;
import com.xncoding.multisource.exception.DataSourceRoutingException;
import com.xncoding.multisource.service.ProbeService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 方案 B（动态路由数据源）的行为验证。
 *
 * <p>这一组测试里，<b>「应该失败」的用例和「应该成功」的用例同样重要</b>。
 * 反例断言的是「路由结果和注解声明不一致」，这是别人不会写进测试、
 * 但恰恰最容易在生产上出问题的那一半。
 *
 * <p>判断路由对不对，依据的是 {@code DATABASE()} 而不是 {@code username}：
 * 数据可以被改，连接所在的库名不能。
 */
@SpringBootTest
@ActiveProfiles("test")
class DynamicRoutingTest {

    @Autowired
    private ProbeService probeService;

    @Test
    @DisplayName("标了 POS 就查交易库")
    void posRoutesToTransactionDatabase() {
        DataSourceProbeResult result = probeService.probePos();

        assertThat(result.actualDatabase()).isEqualTo("springboot4_pos");
        assertThat(result.username()).isEqualTo("admin");
        assertThat(result.routed()).isTrue();
    }

    @Test
    @DisplayName("标了 BIZ 就查运营库，同一句 SQL 同一个 mapper")
    void bizRoutesToOperationDatabase() {
        DataSourceProbeResult result = probeService.probeBiz();

        assertThat(result.actualDatabase()).isEqualTo("springboot4_biz");
        assertThat(result.username()).isEqualTo("admin1");
        assertThat(result.routed()).isTrue();
    }

    @Test
    @DisplayName("两次切换拿到的是不同的物理连接")
    void differentDatabasesUseDifferentConnections() {
        DataSourceProbeResult pos = probeService.probePos();
        DataSourceProbeResult biz = probeService.probeBiz();

        // 两个库是两个连接池，连接号必然不同。
        // 这一条是「真的换了连接」而不是「同一个连接上换了 schema」的证据
        assertThat(pos.connectionId()).isNotEqualTo(biz.connectionId());
    }

    @Test
    @DisplayName("标了一个没注册的库会抛异常，而不是静默回退到默认库")
    void unregisteredKeyThrowsInsteadOfFallingBack() {
        // 注意这里断言的最外层类型不是 DataSourceRoutingException。
        //
        // 路由失败发生在 mapper 取连接的那一刻，而取连接发生在 mapper 方法内部，
        // MyBatis 的 SqlSessionTemplate 会把底层异常交给 MyBatisExceptionTranslator 翻一遍，
        // 翻出来的是 MyBatisSystemException，我们那个异常被塞进了 cause 链。
        //
        // 这个细节是真跑出来的，不是猜的 —— 第一版断言直接写 isInstanceOf(DataSourceRoutingException)
        // 就是红的。它同时说明全局异常处理器如果只按类型接，会接不住（见 ExceptionHandlerTest）。
        Throwable thrown = catchThrowable(() -> probeService.probeUnregisteredKey());

        assertThat(thrown).isInstanceOf(MyBatisSystemException.class);
        assertThat(causeChain(thrown))
                .anyMatch(DataSourceRoutingException.class::isInstance);
        assertThat(thrown).hasStackTraceContaining("REPORT");

        // 关键点在于它是「抛出来」而不是「悄悄回退到交易库返回 admin」——
        // 后者接口会返回 200，从响应上完全看不出问题
        assertThat(thrown).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    /** 把 cause 链摊平成列表，方便断言「链上某一环是不是某个类型」。 */
    private List<Throwable> causeChain(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    @Test
    @DisplayName("反例：同类内部调用，@DataSource 被 AOP 吃掉")
    void selfInvocationIgnoresAnnotation() {
        ProbeScenarioResult scenario = probeService.selfInvocation();

        // 第 1、3 步在外层方法里，B 库
        assertThat(scenario.steps().get(0).routed()).isTrue();
        assertThat(scenario.steps().get(0).actualDatabase()).isEqualTo("springboot4_biz");
        assertThat(scenario.steps().get(2).routed()).isTrue();

        // 第 2 步标的是 POS，但走的是 this.调用，注解不生效，仍在 B 库
        DataSourceProbeResult step2 = scenario.steps().get(1);
        assertThat(step2.expected()).isEqualTo(DataSourceKey.POS);
        assertThat(step2.actualDatabase()).isEqualTo("springboot4_biz");
        assertThat(step2.routed()).isFalse();
    }

    @Test
    @DisplayName("正例：跨 bean 调用注解生效，而且弹栈之后外层不受影响")
    void crossBeanInvocationWorksAndRestoresOuterKey() {
        ProbeScenarioResult scenario = probeService.crossBeanInvocation();

        assertThat(scenario.steps()).hasSize(3);
        assertThat(scenario.steps().get(0).actualDatabase()).isEqualTo("springboot4_biz");
        assertThat(scenario.steps().get(1).actualDatabase()).isEqualTo("springboot4_pos");
        // 第 3 步是关键：内层弹栈之后外层必须还是 BIZ。
        // 如果上下文用的是 set 而不是栈，这里会留在 POS 上
        assertThat(scenario.steps().get(2).actualDatabase()).isEqualTo("springboot4_biz");
        assertThat(scenario.steps()).allMatch(DataSourceProbeResult::routed);
    }

    @Test
    @DisplayName("反例：事务开了之后才切换，切不动")
    void switchingInsideTransactionHasNoEffect() {
        ProbeScenarioResult scenario = probeService.switchInsideTransaction();

        assertThat(scenario.steps().get(0).actualDatabase()).isEqualTo("springboot4_pos");

        DataSourceProbeResult step2 = scenario.steps().get(1);
        assertThat(step2.expected()).isEqualTo(DataSourceKey.BIZ);
        // 注解写了 BIZ，实际还是 POS：事务在方法进入时就借好了连接
        assertThat(step2.actualDatabase()).isEqualTo("springboot4_pos");
        assertThat(step2.routed()).isFalse();
    }

    @Test
    @DisplayName("正例：注解标在事务外面，切换发生在取连接之前")
    void switchingBeforeTransactionWorks() {
        DataSourceProbeResult result = probeService.switchBeforeTransaction();

        assertThat(result.actualDatabase()).isEqualTo("springboot4_biz");
        assertThat(result.routed()).isTrue();
    }

    @Test
    @DisplayName("反例：换线程 ThreadLocal 取不到，静默落回默认库且不报错")
    void contextDoesNotCrossThreadBoundary() {
        ProbeScenarioResult scenario = probeService.probeInAnotherThread();

        assertThat(scenario.steps().get(0).actualDatabase()).isEqualTo("springboot4_biz");

        DataSourceProbeResult step2 = scenario.steps().get(1);
        // 新线程上压根没有标识，所以 expected 是 null
        assertThat(step2.expected()).isNull();
        // 关键点一：没有抛异常
        // 关键点二：落回了默认库 —— 也就是 lenientFallback=false 也拦不住的那个分支
        assertThat(step2.actualDatabase()).isEqualTo("springboot4_pos");

        assertThat(scenario.steps().get(2).actualDatabase()).isEqualTo("springboot4_biz");
    }
}
