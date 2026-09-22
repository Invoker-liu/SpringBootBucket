package com.xncoding.transaction.controller;

import com.xncoding.transaction.dto.ScenarioResult;
import com.xncoding.transaction.failure.ChildThreadService;
import com.xncoding.transaction.failure.ManagerNameService;
import com.xncoding.transaction.failure.NonPublicCaller;
import com.xncoding.transaction.failure.SelfInvocationService;
import com.xncoding.transaction.failure.SwallowedExceptionService;
import com.xncoding.transaction.support.ScenarioRunner;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事务失效场景。这个类里的每一个接口，都会让某个 {@code @Transactional} 白写。
 *
 * <p>它们有个共同特征：<b>不报错</b>。没有异常、没有日志、没有告警，
 * 只有一个后果 —— 该回滚的没回滚。所以判断标准不能是「程序有没有报错」，
 * 只能是「余额对不对」。
 *
 * <p>每个接口的 {@code expectation} 写的是「正经预期」，{@code conclusion} 写的是
 * 实际结论。两者对不上的那几个，就是失效场景本身 —— 它们不是 bug，
 * 是这类问题唯一的症状。
 */
@RestController
@RequestMapping("/api/failure")
public class FailureController {

    private final SelfInvocationService selfInvocationService;
    private final NonPublicCaller nonPublicCaller;
    private final SwallowedExceptionService swallowedExceptionService;
    private final ChildThreadService childThreadService;
    private final ManagerNameService managerNameService;
    private final ScenarioRunner runner;

    public FailureController(SelfInvocationService selfInvocationService,
                             NonPublicCaller nonPublicCaller,
                             SwallowedExceptionService swallowedExceptionService,
                             ChildThreadService childThreadService,
                             ManagerNameService managerNameService,
                             ScenarioRunner runner) {
        this.selfInvocationService = selfInvocationService;
        this.nonPublicCaller = nonPublicCaller;
        this.swallowedExceptionService = swallowedExceptionService;
        this.childThreadService = childThreadService;
        this.managerNameService = managerNameService;
        this.runner = runner;
    }

    // ---------------------------------------------------------------- 同类内部调用

    @PostMapping("/self-invocation")
    public ScenarioResult selfInvocation() {
        return runner.record(
                "失效 · 同类内部调用（this）",
                "正经预期：内层方法抛异常，那 100.00 该回滚",
                "实际失效。this 调的是原始对象，事务拦截器在代理对象上，"
                        + "拦截器没机会介入，方法里那笔 100.00 以自动提交的方式执行完就落库了。",
                steps -> selfInvocationService.thisCall(steps));
    }

    @PostMapping("/self-invocation-fixed")
    public ScenarioResult selfInvocationFixed() {
        return runner.record(
                "修复 · 从容器里取代理再调",
                "预期：内层方法抛异常，那 100.00 回滚",
                "同一段业务代码，只把 this 换成容器里的代理对象，事务就生效了。"
                        + "看 detail 里打印的实际类型，能直接看到拿到的是 CGLIB 代理类。",
                steps -> selfInvocationService.proxyCall(steps));
    }

    // ---------------------------------------------------------------- 方法可见性

    @PostMapping("/public-method")
    public ScenarioResult publicMethod() {
        return runner.record(
                "可见性 · public",
                "预期：事务生效，那 100.00 回滚",
                "基准。public 方法上的 @Transactional 正常生效。",
                steps -> nonPublicCaller.callPublic(steps));
    }

    @PostMapping("/protected-method")
    public ScenarioResult protectedMethod() {
        return runner.record(
                "可见性 · protected",
                "流传的说法：失效。实测预期：生效，那 100.00 回滚",
                "和「非 public 方法事务失效」这句流传很广的话相反。"
                        + "@EnableTransactionManagement 用的是 "
                        + "new AnnotationTransactionAttributeSource(false)，"
                        + "publicMethodsOnly 为 false，非 public 方法一样会被拦截器考虑；"
                        + "CGLIB 又能覆写 protected 方法，两个条件都满足，所以生效。",
                steps -> nonPublicCaller.callProtected(steps));
    }

    @PostMapping("/package-private-method")
    public ScenarioResult packagePrivateMethod() {
        return runner.record(
                "可见性 · 包私有",
                "预期：生效，那 100.00 回滚",
                "同上。包私有方法同样能被 CGLIB 覆写，同样会被事务拦截器处理。",
                steps -> nonPublicCaller.callPackagePrivate(steps));
    }

    @PostMapping("/final-method")
    public ScenarioResult finalMethod() {
        return runner.record(
                "失效 · public final（依赖走实例字段）",
                "预期：失效，那 100.00 留在库里；但实际会先抛 NPE",
                "final 方法不能被覆写，调用落在代理实例自己身上。而 CGLIB 代理是用 Objenesis "
                        + "造出来的、不走构造器，所以代理实例的字段全是 null —— 于是依赖注入看起来"
                        + "「失效了」，抛 NPE。报错和根因完全不在一个地方，这条最容易把人带偏。",
                steps -> nonPublicCaller.callFinal(steps));
    }

    @PostMapping("/final-method-with-params")
    public ScenarioResult finalMethodWithParams() {
        return runner.record(
                "失效 · public final（依赖走参数）",
                "预期：失效，那 100.00 留在库里",
                "把依赖从实例字段换成方法参数之后，NPE 消失，真实结果露出来："
                        + "事务没生效，100.00 留下了。所以「final 方法上标事务会失效」这个结论本身是对的，"
                        + "只是它通常以 NPE 的形式表现出来，而不是以「数据不一致」的形式。",
                steps -> nonPublicCaller.callFinalWithParams(steps));
    }

    @PostMapping("/static-method")
    public ScenarioResult staticMethod() {
        return runner.record(
                "可见性 · static",
                "预期：失效，那 100.00 留在库里",
                "静态方法在编译期就绑定了类型，通过实例引用调用也会被编译器还原成类调用，"
                        + "代理对象完全没有参与的机会。好在静态方法不能读实例字段，"
                        + "不会像 final 那样撞上「代理实例字段全是 null」的 NPE，"
                        + "失效得干干净净。",
                steps -> nonPublicCaller.callStatic(steps));
    }

    // ---------------------------------------------------------------- 异常被吞

    @PostMapping("/swallowed")
    public ScenarioResult swallowed() {
        return runner.record(
                "失效 · 异常被 catch 吞掉",
                "正经预期：出错了就该回滚",
                "实际失效。回滚判定完全建立在「有没有异常穿出被代理的方法」上，"
                        + "catch 住不再抛，事务管理器全程不知情，到点照常提交。"
                        + "这个坑最难查的地方在于它没有任何症状 —— 余额少了 100.00，"
                        + "接口返回 200，日志干干净净。",
                steps -> swallowedExceptionService.swallow(steps));
    }

    @PostMapping("/rethrow")
    public ScenarioResult rethrow() {
        return runner.record(
                "修复 · catch 之后抛出去",
                "预期：回滚，那 100.00 回来",
                "最直接的修法。抛的还是原来那个运行时异常，回滚判定正常工作。",
                steps -> swallowedExceptionService.rethrow(steps));
    }

    @PostMapping("/rethrow-as-checked")
    public ScenarioResult rethrowAsChecked() {
        return runner.record(
                "失效 · 包成受检异常再抛",
                "直觉预期：抛出去了就该回滚。实际失效，那 100.00 留下",
                "比「忘了抛」更隐蔽：异常确实抛出去了，调用方也收到了。"
                        + "但类型从 RuntimeException 换成了受检异常，默认回滚规则就不认了。"
                        + "为了匹配方法签名而顺手做的一次包装，足以把回滚弄丢。",
                steps -> swallowedExceptionService.rethrowAsChecked(steps));
    }

    @PostMapping("/swallowed-but-marked")
    public ScenarioResult swallowedButMarked() {
        return runner.record(
                "修复 · 吞掉异常但标记 rollback-only",
                "预期：回滚，那 100.00 回来；同时方法正常返回不抛异常",
                "适合「异常要转成业务返回值，但事务必须回滚」的场景。"
                        + "注意方法正常返回和事务回滚并不冲突 —— setRollbackOnly "
                        + "是告诉事务管理器「提交的时候改主意」，不是抛异常。",
                steps -> swallowedExceptionService.swallowButMarkRollbackOnly(steps));
    }

    // ---------------------------------------------------------------- 换线程

    @PostMapping("/child-thread")
    public ScenarioResult childThread() {
        return runner.record(
                "失效 · 在子线程里写库",
                "正经预期：主线程失败就整体回滚",
                "实际失效。事务状态存在 ThreadLocal 里，跨不了线程。"
                        + "子线程读到的事务状态是「无」，它从连接池另借一条连接、自动提交，"
                        + "于是主线程回滚了，子线程那 50.00 留在库里。"
                        + "换成虚拟线程、@Async、并行流，结果一模一样 —— 绑的是线程，不是具体哪种线程。",
                steps -> childThreadService.writeInChildThread(steps));
    }

    @PostMapping("/child-thread-with-own-transaction")
    public ScenarioResult childThreadWithOwnTransaction() {
        return runner.record(
                "修复 · 给子线程自己的事务",
                "预期：主线程回滚，子线程独立提交，那 50.00 留下",
                "注意这里的「修复」修的不是原子性，跨线程没有原子性可言。"
                        + "它修的是「子线程里的多个写操作要么一起成功要么一起失败」。"
                        + "主事务和子事务之间的一致性，得靠补偿、对账这类业务手段处理，"
                        + "指望配置解决是走不通的。",
                steps -> childThreadService.writeInChildThreadWithOwnTransaction(steps));
    }

    // ---------------------------------------------------------------- 事务管理器名字

    @PostMapping("/manager-anonymous")
    public ScenarioResult managerAnonymous() {
        return runner.record(
                "管理器 · 不写名字",
                "预期：按类型找到唯一的事务管理器，正常提交",
                "不写名字时走的是「按类型找唯一实例」。容器里正好一个，所以没事。"
                        + "一旦引入第二个数据源，这里会变成 NoUniqueBeanDefinitionException。",
                steps -> managerNameService.anonymous(steps));
    }

    @PostMapping("/manager-named")
    public ScenarioResult managerNamed() {
        return runner.record(
                "管理器 · 写对名字",
                "预期：按名字取到，正常提交",
                "value 和 transactionManager 互为 @AliasFor，"
                        + "@Transactional(\"transactionManager\") 与 "
                        + "@Transactional(transactionManager = \"transactionManager\") 完全等价。",
                steps -> managerNameService.named(steps));
    }

    @PostMapping("/manager-wrong-name")
    public ScenarioResult managerWrongName() {
        return runner.record(
                "失效 · 事务管理器名字写错",
                "预期：抛 NoSuchBeanDefinitionException",
                "名字写错不会在启动时报错，只在第一次调用这个方法时才炸 —— "
                        + "编译通过、启动通过、没覆盖到的接口全都「看起来正常」。"
                        + "而且事务管理器在进方法之前就要确定，所以方法体里那笔转账根本不会执行。",
                steps -> managerNameService.wrongName(steps));
    }
}
