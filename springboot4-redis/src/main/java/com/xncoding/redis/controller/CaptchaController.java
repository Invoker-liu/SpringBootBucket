package com.xncoding.redis.controller;

import com.xncoding.redis.dto.CaptchaSendResponse;
import com.xncoding.redis.dto.CaptchaStatusResponse;
import com.xncoding.redis.dto.SendCaptchaRequest;
import com.xncoding.redis.dto.VerifyCaptchaRequest;
import com.xncoding.redis.dto.VerifyResponse;
import com.xncoding.redis.service.CaptchaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 验证码接口。注意 Controller 类上没有 {@code @Validated}（原因见 GlobalExceptionHandler）。
 */
@RestController
@RequestMapping("/api/captchas")
public class CaptchaController {

    private final CaptchaService captchaService;

    public CaptchaController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    /**
     * 发送验证码：60 秒冷却，5 分钟有效。
     */
    @PostMapping
    public ResponseEntity<CaptchaSendResponse> send(@Valid @RequestBody SendCaptchaRequest request) {
        CaptchaService.CaptchaSendResult result = captchaService.send(request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CaptchaSendResponse(
                result.email(), result.expiresInSecond(), result.resendAfterSecond()));
    }

    /**
     * 校验验证码：正确即销毁，错误累计次数，错满 5 次作废。
     */
    @PostMapping("/verify")
    public VerifyResponse verify(@Valid @RequestBody VerifyCaptchaRequest request) {
        boolean verified = captchaService.verify(request.email(), request.code());
        return new VerifyResponse(request.email(), verified);
    }

    /**
     * 查询验证码状态：剩余有效期、剩余尝试次数。
     */
    @GetMapping("/{email}")
    public CaptchaStatusResponse status(@PathVariable String email) {
        CaptchaService.CaptchaStatusResult result = captchaService.status(email);
        return new CaptchaStatusResponse(result.email(), result.remainingSecond(),
                result.remainingAttempts(), result.createdAt());
    }

    /**
     * 手动作废验证码。
     */
    @DeleteMapping("/{email}")
    public ResponseEntity<Void> invalidate(@PathVariable String email) {
        captchaService.invalidate(email);
        return ResponseEntity.noContent().build();
    }
}
