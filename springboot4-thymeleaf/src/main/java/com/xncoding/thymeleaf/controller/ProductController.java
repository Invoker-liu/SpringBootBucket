package com.xncoding.thymeleaf.controller;

import com.xncoding.thymeleaf.domain.Product;
import com.xncoding.thymeleaf.form.ProductForm;
import com.xncoding.thymeleaf.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 商品管理页面控制器。
 * <p>
 * 关键点：GET 负责渲染表单（往模型里放空的 form 对象供 th:object 绑定），
 * POST 负责接收提交；校验失败时带着 BindingResult 重新渲染表单页，
 * 成功时走「提交-重定向-闪属性」模式跳回列表页。
 */
@Controller
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /** 列表页：支持关键词过滤与分页，模板按 Page 切片渲染表格与翻页链接 */
    @GetMapping
    public String list(@RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "8") int size,
                       @RequestParam(required = false) String keyword,
                       Model model) {
        model.addAttribute("pageResult", productService.page(page, size, keyword));
        model.addAttribute("keyword", keyword == null ? "" : keyword);
        return "products/list";
    }

    /** 新增页：必须先往模型放一个空表单对象，否则 th:object 解析不到 */
    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", ProductForm.empty());
        return "products/form";
    }

    /** 新增提交：注解校验 + 业务校验（重名），失败回显表单，成功重定向 */
    @PostMapping
    public String create(@Valid @ModelAttribute("form") ProductForm form,
                         BindingResult binding,
                         RedirectAttributes redirectAttributes) {
        if (productService.existsByName(form.getName(), null)) {
            binding.rejectValue("name", "Duplicate", "同名商品已存在");
        }
        if (binding.hasErrors()) {
            return "products/form";
        }
        Product created = productService.create(form);
        redirectAttributes.addFlashAttribute("message", "已新增商品：" + created.getName());
        return "redirect:/products";
    }

    /** 编辑页：把实体字段拷进表单对象，让 th:field 自动回显 */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        Product product = productService.getById(id);
        ProductForm form = new ProductForm();
        form.setId(product.getId());
        form.setName(product.getName());
        form.setCategory(product.getCategory());
        form.setPrice(product.getPrice());
        form.setStock(product.getStock());
        form.setSummary(product.getSummary());
        model.addAttribute("form", form);
        return "products/form";
    }

    /** 编辑提交：与新增共用一个 form.html，模板里按 form.id 是否存在区分提交地址 */
    @PostMapping("/{id}")
    public String edit(@PathVariable Long id,
                       @Valid @ModelAttribute("form") ProductForm form,
                       BindingResult binding,
                       RedirectAttributes redirectAttributes) {
        form.setId(id);
        if (productService.existsByName(form.getName(), id)) {
            binding.rejectValue("name", "Duplicate", "同名商品已存在");
        }
        if (binding.hasErrors()) {
            return "products/form";
        }
        productService.update(id, form);
        redirectAttributes.addFlashAttribute("message", "已更新商品：" + form.getName());
        return "redirect:/products";
    }
}
