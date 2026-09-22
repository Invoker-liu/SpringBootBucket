package com.xncoding.multisource;

import com.xncoding.multisource.routing.DataSourceAspect;
import com.xncoding.multisource.service.ProbeService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断言「数据源切换的 advice 排在事务 advice 之前」—— 结构上确认，不靠推断。
 *
 * <p>这件事是动态方案能否使用的前提。手工写 {@code @Order(0)} 的时候很容易想当然，
 * 而且如果哪天有人往切面上加了个 {@code @Order(100)} 或者把注解换成
 * {@code Ordered.LOWEST_PRECEDENCE}，代码照样编译、照样启动，
 * 只有某个接口开始查错库。所以这里把它固定成测试。
 *
 * <p>{@code Advised#getAdvisors()} 返回的顺序已经是排好序的，
 * order 小的在前；advice 是「外面的先执行」，所以下标越小越靠外、越先跑。
 * 两个断言都写：一个比 order 值，一个比下标，任何一个被改动都会红。
 *
 * <p>行为层面的验证在 {@link DynamicRoutingTest#switchingBeforeTransactionWorks()}，
 * 那个用例在顺序被改反之后会直接失败。
 */
@SpringBootTest
@ActiveProfiles("test")
class AdvisorOrderTest {

    @Autowired
    private ProbeService probeService;

    @Test
    @DisplayName("数据源切面的 order 小于事务切面，且排得更靠外")
    void dataSourceAdviceRunsBeforeTransactionAdvice() {
        assertThat(probeService).isInstanceOf(Advised.class);
        Advisor[] advisors = ((Advised) probeService).getAdvisors();

        List<String> actualOrder = new ArrayList<>();
        int dataSourceIndex = -1;
        int transactionIndex = -1;

        for (int i = 0; i < advisors.length; i++) {
            Advisor advisor = advisors[i];
            actualOrder.add(i + ": order=" + orderOf(advisor) + " " + describe(advisor));

            if (dataSourceIndex < 0 && isDataSourceAspectAdvisor(advisor)) {
                dataSourceIndex = i;
            }
            if (transactionIndex < 0 && advisor instanceof BeanFactoryTransactionAttributeSourceAdvisor) {
                transactionIndex = i;
            }
        }

        assertThat(dataSourceIndex)
                .as("代理上应该挂着 DataSourceAspect 的 advisor，实际链：" + actualOrder)
                .isNotNegative();
        assertThat(transactionIndex)
                .as("代理上应该挂着事务 advisor，实际链：" + actualOrder)
                .isNotNegative();

        assertThat(orderOf(advisors[dataSourceIndex]))
                .as("数据源切面的 order 必须小于事务 advisor 的 order，实际链：" + actualOrder)
                .isLessThan(orderOf(advisors[transactionIndex]));

        assertThat(dataSourceIndex)
                .as("数据源切面必须排在事务切面外面，实际链：" + actualOrder)
                .isLessThan(transactionIndex);

        assertThat(DataSourceAspect.ORDER).isZero();
    }

    /** 切面自己声明的是哪个 order，和代理链上实际生效的值应该是同一个。 */
    @Test
    @DisplayName("代理链上生效的 order 就是切面上 @Order 声明的那个值")
    void advisorOrderMatchesAnnotation() {
        Advisor[] advisors = ((Advised) probeService).getAdvisors();
        for (Advisor advisor : advisors) {
            if (isDataSourceAspectAdvisor(advisor)) {
                assertThat(orderOf(advisor)).isEqualTo(DataSourceAspect.ORDER);
                return;
            }
        }
        throw new AssertionError("没有在代理链上找到 DataSourceAspect 的 advisor");
    }

    /**
     * 取 advisor 的 order。
     *
     * <p>{@code Advisor} 这个接口本身<b>没有</b> {@code getOrder()}，
     * 是那些具体实现各自实现了 {@code Ordered}（{@code DefaultPointcutAdvisor}、
     * {@code AspectJPointcutAdvisor}、{@code BeanFactoryTransactionAttributeSourceAdvisor} 都是）。
     * 所以这里必须先 instanceof 再调用，不能直接 {@code advisor.getOrder()} ——
     * 编译器会拦下来，这也是本次写测试时实际踩到的一处。
     */
    private int orderOf(Advisor advisor) {
        return advisor instanceof Ordered ordered ? ordered.getOrder() : Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 判断某个 advisor 是不是 {@code DataSourceAspect} 贡献的。
     *
     * <p>不按类名硬匹配 —— {@code AspectJPointcutAdvisor} 这个类型本身
     * 是所有 {@code @Aspect} 共用的。看切点表达式里有没有我们的注解类型更可靠。
     */
    private boolean isDataSourceAspectAdvisor(Advisor advisor) {
        if (!(advisor instanceof PointcutAdvisor pointcutAdvisor)) {
            return false;
        }
        return pointcutAdvisor.getPointcut().toString()
                .contains("com.xncoding.multisource.annotation.DataSource");
    }

    private String describe(Advisor advisor) {
        return advisor.getClass().getSimpleName();
    }
}
