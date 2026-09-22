package com.xncoding.thymeleaf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Thymeleaf 服务端渲染示例应用。
 * <p>
 * 商品运营后台：列表分页、新增/编辑表单（Bean Validation 校验）、提交后重定向闪属性。
 */
@SpringBootApplication
public class ThymeleafApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThymeleafApplication.class, args);
    }
}
