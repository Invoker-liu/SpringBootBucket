package com.xncoding.multisource.controller;

import com.xncoding.multisource.dto.DataSourceProbeResult;
import com.xncoding.multisource.dto.ProbeScenarioResult;
import com.xncoding.multisource.service.ProbeService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 方案 B（动态路由数据源）的接口。
 *
 * <p>这里的所有接口都由 {@link ProbeService} 提供服务，但注意
 * <b>数据源的切换不是在这些方法里发生的</b>。控制器这一层完全干净，
 * 它只管调服务，切库的事由服务类上的 {@code @DataSource} 注解和切面完成。
 * 这就是动态方案相对静态方案最实际的收益：
 * <b>数据源的选择从依赖注入关系里挪到了一个注解上</b>。
 *
 * <p>另外注意「正例」和「反例」的区分：
 * 反例接口返回 200 是很正常的，它要证明的恰恰是「看起来正常，其实错了」。
 * 只有 {@code /probe/unregistered} 是会真的报错的。
 */
@RestController
@RequestMapping("/api/dynamic")
public class DynamicDataSourceController {

    private final ProbeService probeService;

    public DynamicDataSourceController(ProbeService probeService) {
        this.probeService = probeService;
    }

    /** 切到交易库。 */
    @GetMapping("/probe/pos")
    public DataSourceProbeResult probePos() {
        return probeService.probePos();
    }

    /** 切到运营库。同一句 SQL、同一个 mapper，只是注解不同。 */
    @GetMapping("/probe/biz")
    public DataSourceProbeResult probeBiz() {
        return probeService.probeBiz();
    }

    /**
     * 一次请求里先后跑两个库。
     *
     * <p>这两个调用都经过代理，所以各自切换、各自弹回，互不影响。
     * 同一个 HTTP 请求、同一个线程，两次查询落在两个库上。
     */
    @GetMapping("/probe/both")
    public List<DataSourceProbeResult> probeBoth() {
        return List.of(probeService.probePos(), probeService.probeBiz());
    }

    /** 标了一个没注册进 targetDataSources 的库：会抛 DataSourceRoutingException，返回 500。 */
    @GetMapping("/probe/unregistered")
    public DataSourceProbeResult probeUnregistered() {
        return probeService.probeUnregisteredKey();
    }

    /** 反例：同类内部调用，注解被 AOP 吃掉，切不过去。 */
    @GetMapping("/probe/self-invocation")
    public ProbeScenarioResult selfInvocation() {
        return probeService.selfInvocation();
    }

    /** 正例：跨 bean 调用，注解生效，而且弹栈之后外层不受影响。 */
    @GetMapping("/probe/cross-bean")
    public ProbeScenarioResult crossBean() {
        return probeService.crossBeanInvocation();
    }

    /** 反例：事务开了之后才切换，连接已经绑好，切不动。 */
    @GetMapping("/probe/switch-inside-transaction")
    public ProbeScenarioResult switchInsideTransaction() {
        return probeService.switchInsideTransaction();
    }

    /** 正例：注解标在事务外面，切换发生在取连接之前。 */
    @GetMapping("/probe/switch-before-transaction")
    public DataSourceProbeResult switchBeforeTransaction() {
        return probeService.switchBeforeTransaction();
    }

    /** 反例：换线程，ThreadLocal 取不到，静默落回默认库。 */
    @GetMapping("/probe/another-thread")
    public ProbeScenarioResult anotherThread() {
        return probeService.probeInAnotherThread();
    }
}
