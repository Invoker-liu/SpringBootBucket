package com.xncoding.thymeleaf.web;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局配置：页面公共属性 + 校验消息的中文绑定。
 */
@Configuration
public class WebConfig {

    /**
     * 把 Bean Validation 的消息解析接到 Spring MessageSource 上，
     * 这样 typeMismatch 这类类型转换错误才能命中 messages.properties 里的中文文案。
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    @Bean
    public LocalValidatorFactoryBean validator(MessageSource messageSource) {
        LocalValidatorFactoryBean factoryBean = new LocalValidatorFactoryBean();
        factoryBean.setValidationMessageSource(messageSource);
        return factoryBean;
    }

    /**
     * 全局页面属性：所有视图都能取到 appName，导航条直接展示。
     */
    @ControllerAdvice
    public static class GlobalPageAdvice {

        @ModelAttribute("appName")
        public String appName() {
            return "商品运营后台";
        }
    }
}
