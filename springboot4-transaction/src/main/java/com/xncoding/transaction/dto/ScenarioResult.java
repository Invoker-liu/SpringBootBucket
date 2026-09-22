package com.xncoding.transaction.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一个完整演示场景的结果。
 *
 * <p>所有场景接口都返回这个结构，是为了让读者能一眼对照「预期」和「实际」：
 * 光看余额变化说明不了什么，得先知道这个场景本来应该是什么结果。
 *
 * @param scenario    场景标识
 * @param expectation 这个场景的预期结果（写成一句人话）
 * @param steps       分步记录
 * @param conclusion  实际结果与预期的对照结论
 * @param accounts    执行完之后四个账户的余额
 * @param total       四个账户余额之和。转账不改变总额，所以它是最好的「有没有多出来或少了」的判据
 */
public record ScenarioResult(
        String scenario,
        String expectation,
        List<StepResult> steps,
        String conclusion,
        List<AccountView> accounts,
        BigDecimal total
) {
}
