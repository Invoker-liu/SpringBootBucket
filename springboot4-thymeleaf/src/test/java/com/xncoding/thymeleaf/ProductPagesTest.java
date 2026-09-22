package com.xncoding.thymeleaf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品页面端到端测试：列表渲染、校验失败回显、成功提交重定向闪属性。
 * <p>
 * 口径说明：服务端渲染场景下，POST 校验失败返回的是 200（带着错误信息重新渲染表单），
 * 不是 REST 风格的 400/422——这是模板驱动开发与接口开发的根本差异之一。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductPagesTest {

    @Autowired
    private MockMvcTester mockMvc;

    private String bodyText(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("列表页：返回 200，表格含预置数据与翻页信息")
    void listRendersTableWithSeedData() {
        MvcTestResult result = mockMvc.get().uri("/products").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("商品管理");
        assertThat(body).containsPattern("共 \\d+ 条");
        assertThat(body).containsPattern("共 \\d+ 条");
        assertThat(body).contains("下一页");
    }

    @Test
    @DisplayName("列表页：第二页只有剩余 4 条，翻页链接带 page 参数")
    void listSecondPageShowsRemainingRows() {
        MvcTestResult result = mockMvc.get().uri("/products?page=2").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("机械键盘");
        assertThat(body).contains("page=1");
    }

    @Test
    @DisplayName("列表页：关键词过滤后表格只剩匹配行")
    void listFiltersByKeyword() {
        MvcTestResult result = mockMvc.get().uri("/products?keyword=咖啡").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("挂耳咖啡");
        assertThat(body).doesNotContain("无线鼠标");
    }

    @Test
    @DisplayName("新增页：返回 200，表单含空值输入项且没有错误提示")
    void createFormRendersEmpty() {
        MvcTestResult result = mockMvc.get().uri("/products/new").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("新增商品");
        assertThat(body).contains("商品名称");
        assertThat(body).doesNotContain("class=\"error\"");
    }

    @Test
    @DisplayName("空表单提交：返回 200 回显表单，逐字段展示校验文案")
    void emptySubmitRendersFieldErrors() {
        MvcTestResult result = mockMvc.post().uri("/products").exchange();
        String body = bodyText(result);

        // 服务端渲染口径：校验失败不是 4xx，而是 200 + 表单重渲染
        result.assertThat().hasStatusOk();
        assertThat(body).contains("商品名称不能为空");
        assertThat(body).contains("售价不能为空");
        assertThat(body).contains("库存不能为空");
        assertThat(body).contains("商品分类不能为空");
    }

    @Test
    @DisplayName("非法值提交：长度、枚举、数值下限、类型转换四类错误同时回显")
    void invalidValuesRenderAllKindsOfErrors() {
        MvcTestResult result = mockMvc.post().uri("/products")
                .param("name", "X")
                .param("category", "UNKNOWN")
                .param("price", "abc")
                .param("stock", "-5")
                .param("summary", "x".repeat(60))
                .exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("商品名称长度需在 2 到 20 个字符之间");
        assertThat(body).contains("商品分类必须是预置的四类之一");
        // price 传入 abc，属类型转换失败，命中 messages.properties 的 typeMismatch 文案
        assertThat(body).contains("售价格式不正确");
        assertThat(body).contains("库存不能为负数");
        assertThat(body).contains("简介不能超过 50 个字符");
    }

    @Test
    @DisplayName("合法提交：302 重定向到列表页，闪属性携带成功提示")
    void validSubmitRedirectsWithFlash() {
        MvcTestResult result = mockMvc.post().uri("/products")
                .param("name", "测试蓝牙音箱")
                .param("category", "DIGITAL")
                .param("price", "199.00")
                .param("stock", "30")
                .param("summary", "演示用新增商品")
                .exchange();

        result.assertThat()
                .hasStatus(302)
                .hasRedirectedUrl("/products");
        // flash attribute 放进了本次请求的 FlashMap，下次 GET 列表页时被消费
        Object flashMessage = result.getMvcResult().getFlashMap().get("message");
        assertThat(flashMessage).isEqualTo("已新增商品：测试蓝牙音箱");
    }

    @Test
    @DisplayName("重名提交：业务校验拒绝，表单回显重名提示")
    void duplicateNameIsRejected() {
        mockMvc.post().uri("/products")
                .param("name", "限量样品机")
                .param("category", "DIGITAL")
                .param("price", "99.00")
                .param("stock", "1")
                .exchange();

        MvcTestResult second = mockMvc.post().uri("/products")
                .param("name", "限量样品机")
                .param("category", "DIGITAL")
                .param("price", "99.00")
                .param("stock", "1")
                .exchange();
        String body = bodyText(second);

        second.assertThat().hasStatusOk();
        assertThat(body).contains("同名商品已存在");
    }

    @Test
    @DisplayName("编辑页：回显已有字段值")
    void editFormPrefillsValues() {
        MvcTestResult result = mockMvc.get().uri("/products/1/edit").exchange();
        String body = bodyText(result);

        result.assertThat().hasStatusOk();
        assertThat(body).contains("编辑商品 #1");
        assertThat(body).contains("value=\"无线鼠标\"");
    }

    @Test
    @DisplayName("编辑提交：合法修改后重定向，列表页出现新名称")
    void editSubmitUpdatesAndRedirects() {
        MvcTestResult result = mockMvc.post().uri("/products/1")
                .param("name", "无线鼠标（升级版）")
                .param("category", "DIGITAL")
                .param("price", "149.00")
                .param("stock", "300")
                .param("summary", "升级后的演示商品")
                .exchange();

        result.assertThat()
                .hasStatus(302)
                .hasRedirectedUrl("/products");

        MvcTestResult list = mockMvc.get().uri("/products?keyword=升级版").exchange();
        assertThat(bodyText(list)).contains("无线鼠标（升级版）");
    }
}
