package com.xncoding.mongo.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理器的映射单元测试。
 * <p>
 * 为什么单独写这个类：唯一索引冲突这条路走不到 HTTP 层。
 * 订单号是服务端用"时间戳 + 随机数"生成的，测试里没法让两个请求撞上同一个单号，
 * 所以想验证"驱动抛的 {@link DuplicateKeyException} 会被翻译成 409"，
 * 只能直接把异常丢给处理器。
 * <p>
 * 这也顺带说明了一件事：<b>能被端到端测到的路径是有限的</b>，
 * 剩下那些就补单元测试，别为了"好看"去生产代码里开后门。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("DuplicateKeyException → 409，并且带上 cause")
    void duplicateKeyMapsTo409() {
        DuplicateKeyException ex = new DuplicateKeyException(
                "E11000 duplicate key error collection: springboot4_mongo.orders index: uk_order_no");

        ResponseEntity<ProblemDetail> response = handler.handleDuplicateKey(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getTitle()).isEqualTo("数据完整性冲突");
        assertThat(body.getType()).hasToString("urn:problem-type:duplicate-key");
        assertThat(String.valueOf(body.getProperties().get("cause"))).contains("uk_order_no");
    }

    @Test
    @DisplayName("OptimisticLockingFailureException → 409，不带 entity 属性")
    void optimisticLockMapsTo409() {
        OptimisticLockingFailureException ex =
                new OptimisticLockingFailureException("Optimistic lock failed");

        ResponseEntity<ProblemDetail> response = handler.handleOptimisticLock(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getType()).hasToString("urn:problem-type:concurrent-modification");
        // 上一篇这个位置有个 entity 属性，来自子类 ObjectOptimisticLockingFailureException。
        // 父类没有那个方法，所以这里一个属性都没设，getProperties() 直接是 null。
        // 语义概念相同、可拿到的信息不同，这正是"两个模块不是简单换个包名"的一个小注脚。
        Map<String, Object> properties = body.getProperties();
        assertThat(properties == null ? Map.of() : properties).doesNotContainKey("entity");
    }

    @Test
    @DisplayName("ResourceNotFoundException → 404，带 resourceType 与 resourceId")
    void notFoundMapsTo404() {
        ResponseEntity<ProblemDetail> response =
                handler.handleResourceNotFound(new ResourceNotFoundException("订单", "abc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ProblemDetail body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getDetail()).isEqualTo("订单不存在：abc");
        assertThat(body.getProperties()).containsEntry("resourceType", "订单")
                .containsEntry("resourceId", "abc");
    }

    @Test
    @DisplayName("BusinessException → 状态码由异常自带（409 / 422）")
    void businessExceptionKeepsItsOwnStatus() {
        assertThat(handler.handleBusiness(BusinessException.conflict("撞了")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(handler.handleBusiness(BusinessException.unprocessable("不行")).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
