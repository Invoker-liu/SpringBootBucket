package com.xncoding.transaction.failure;

import com.xncoding.transaction.dto.StepResult;
import com.xncoding.transaction.service.AccountService;
import com.xncoding.transaction.support.TxProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 失效场景二：方法可见性。
 *
 * <p>「{@code @Transactional} 只能写在 public 方法上」这个说法流传极广，
 * 但它是个半对半错的结论，得拆成两个条件来看，缺一不可：
 *
 * <ol>
 *   <li><b>Spring 会不会把它当成事务方法</b> —— 由 {@code AnnotationTransactionAttributeSource}
 *       的 {@code publicMethodsOnly} 决定。这个字段的默认值确实是 {@code true}，
 *       但 {@code @EnableTransactionManagement} 走的不是无参构造，而是
 *       {@code new AnnotationTransactionAttributeSource(false)}，
 *       而且从 Spring 6.0 就是这样。所以在 Boot 里，<b>非 public 方法也会被事务拦截器考虑。</b>
 *       （早期版本和 {@code mode = ASPECTJ} 的配置不走这条路，那是另一回事。）</li>
 *   <li><b>CGLIB 能不能覆写它</b> —— 代理只能拦下自己能覆写的方法。
 *       {@code private}、{@code final}、{@code static} 都不行。</li>
 * </ol>
 *
 * <p>两条合起来，真实结果是：
 *
 * <table border="1">
 *   <caption>各可见性下的事务效果</caption>
 *   <tr><th>修饰符</th><th>Spring 当事务方法</th><th>CGLIB 能覆写</th><th>实际结果</th></tr>
 *   <tr><td>{@code public}</td><td>是</td><td>是</td><td>生效</td></tr>
 *   <tr><td>{@code protected}</td><td>是</td><td>是</td><td><b>生效</b></td></tr>
 *   <tr><td>包私有</td><td>是</td><td>是</td><td><b>生效</b></td></tr>
 *   <tr><td>{@code public final}</td><td>是</td><td>否</td><td>失效</td></tr>
 *   <tr><td>{@code static}</td><td>是</td><td>否</td><td>失效</td></tr>
 *   <tr><td>{@code private}</td><td>是</td><td>否</td><td>失效</td></tr>
 * </table>
 *
 * <p>中间两行是反直觉的，也是这个类存在的理由 —— 光靠背结论很容易背成
 * 「非 public 全失效」，然后在 {@code protected} 方法上白折腾半天。
 *
 * <p>这张表有个前提：<b>它成立是因为走了 CGLIB 代理</b>。如果把
 * {@code spring.aop.proxy-target-class} 设成 {@code false} 改用 JDK 动态代理，
 * 这些非 public 方法在代理对象上根本不存在，会一起失效。Boot 的默认值是
 * {@code true}，所以上表就是默认情况。
 *
 * <p>另外补一句工程上的话：{@code protected} 虽然能用，但别这么写。
 * 事务边界写在非 public 方法上，意味着只有同一个包里的代码才调得到它，
 * 而「我这个方法会开事务」这件事对跨包的调用者完全不可见。
 *
 * <p>方法都接收一个 {@code steps} 参数，是为了把探针读到的事务状态<b>在方法内部</b>
 * 记下来 —— 出了方法事务就结束了，那时候再读只能读到「没有事务」，没有信息量。
 */
@Service
public class NonPublicService {

    static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private final AccountService accountService;
    private final TxProbe probe;

    public NonPublicService(AccountService accountService, TxProbe probe) {
        this.accountService = accountService;
        this.probe = probe;
    }

    /** 统一动作：读一次事务状态，转 100.00，然后抛异常。有事务就回滚，没事务就留下。 */
    private void chargeAndFail(List<StepResult> steps, String label) {
        steps.add(new StepResult(label + " · 方法内部", probe.summary(), "接下来转 100.00 并抛异常"));
        accountService.move(1L, 2L, AMOUNT);
        throw new IllegalStateException(label + "：转账后故意失败");
    }

    @Transactional
    public void publicMethod(List<StepResult> steps) {
        chargeAndFail(steps, "public");
    }

    @Transactional
    protected void protectedMethod(List<StepResult> steps) {
        chargeAndFail(steps, "protected");
    }

    @Transactional
    void packagePrivateMethod(List<StepResult> steps) {
        chargeAndFail(steps, "包私有");
    }

    /**
     * {@code final} 挡住了 CGLIB 覆写，注解写在这儿等于没写。
     *
     * <p>而且它的报错方式很特别：<b>不是「静默失效」，而是 NullPointerException。</b>
     * 原因在代理对象的构造方式上 —— Spring 用 Objenesis 创建 CGLIB 代理实例，
     * 不走目标类的构造器，所以<b>代理对象自己的实例字段全是 null</b>。
     * 正常调用时代理把请求转给真正的目标对象（字段在它身上），所以看不出来；
     * 但 {@code final} 方法覆写不了，调用会落在<b>代理实例自己</b>身上，
     * 于是 {@code this.probe}、{@code this.accountService} 统统是 null。
     *
     * <p>这一条排错时特别容易把人带偏：报的是 NPE，看起来像依赖注入没生效，
     * 根因却是「方法被声明成了 final」。
     *
     * <p>下面 {@link #finalMethodWithParams} 是同一个方法的另一半：
     * 一旦把依赖从实例字段换成方法参数，NPE 消失，露出真正的结果 ——
     * 事务没生效，钱留下了。
     */
    @Transactional
    public final void finalMethod(List<StepResult> steps) {
        chargeAndFail(steps, "public final");
    }

    /**
     * 把依赖全部改成参数之后，{@code final} 方法的真实结果才露出来。
     *
     * <p>这个方法不读任何实例字段，所以即使运行在「字段全是 null 的代理实例」上也安全。
     * 代价是连探针状态都读不了 —— 探针本身就是个字段，只能由调用方读好传进来。
     */
    @Transactional
    public final void finalMethodWithParams(List<StepResult> steps, AccountService accountService, TxProbe probe) {
        steps.add(new StepResult("public final · 方法内部（依赖走参数）", probe.summary(),
                "接下来转 100.00 并抛异常"));
        accountService.move(1L, 2L, AMOUNT);
        throw new IllegalStateException("public final：转账后故意失败");
    }

    /**
     * {@code static} 方法不属于任何对象，代理无从下手。
     *
     * <p>写成静态还连带一个尴尬：它没有实例字段可用，依赖只能从参数传进来。
     * 一个「开了事务」的静态方法连自己要用的 Service 都拿不到，这本身就说明位置不对。
     */
    @Transactional
    public static void staticMethod(List<StepResult> steps, AccountService accountService, TxProbe probe) {
        steps.add(new StepResult("static · 方法内部", probe.summary(), "接下来转 100.00 并抛异常"));
        accountService.move(1L, 2L, AMOUNT);
        throw new IllegalStateException("static：转账后故意失败");
    }
}
