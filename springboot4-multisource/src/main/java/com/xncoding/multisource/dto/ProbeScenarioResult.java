package com.xncoding.multisource.dto;

import java.util.List;

/**
 * 一个「切换数据源」的场景，由若干步组成。
 *
 * <p>之所以要用「场景 + 步骤」两层，是因为要证明的东西往往不是单次查询能说清的。
 * 比如嵌套切换必须展示「外层 → 内层 → 回到外层」三步都落在哪个库，
 * 只看最后一次结果是看不出中间有没有出问题的。
 *
 * @param scenario   场景名
 * @param expectation 这个场景预期看到什么
 * @param conclusion 实际看到什么
 * @param steps      逐步的快照
 */
public record ProbeScenarioResult(
        String scenario,
        String expectation,
        String conclusion,
        List<DataSourceProbeResult> steps) {
}
