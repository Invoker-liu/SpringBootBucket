package com.xncoding.multisource.service;

import com.xncoding.multisource.annotation.DataSource;
import com.xncoding.multisource.context.DataSourceKey;
import com.xncoding.multisource.dto.DataSourceProbeResult;
import com.xncoding.multisource.mapper.probe.ProbeUserMapper;

import org.springframework.stereotype.Service;

/**
 * 一个<b>独立</b>的 bean，存在的唯一理由是让 {@code ProbeService} 能跨 bean 调到它。
 *
 * <p>为什么非得拆一个类出来：{@code @DataSource} 是靠 Spring AOP 生效的，
 * 而 AOP 生效的前提是调用经过代理对象。同一个类里 {@code this.别的@DataSource方法()}
 * 是直接调用，压根不经过代理，注解会被完全忽略。
 * {@code @Transactional}、{@code @Cacheable}、{@code @Async} 全都有这个毛病，
 * 属于 Spring 里最容易翻车的一类坑。
 *
 * <p>工程里 {@code ProbeService#selfInvocation} 和 {@code #crossBeanInvocation}
 * 就是这一对正反例，跑一遍就能看出差别。
 */
@Service
public class ProbeInnerService {

    private final ProbeUserMapper probeUserMapper;

    public ProbeInnerService(ProbeUserMapper probeUserMapper) {
        this.probeUserMapper = probeUserMapper;
    }

    /** 从别的 bean 调进来时，这个注解才会真正生效。 */
    @DataSource(DataSourceKey.POS)
    public DataSourceProbeResult probePos(String label) {
        return ProbeQueries.query(probeUserMapper, label, DataSourceKey.POS);
    }
}
