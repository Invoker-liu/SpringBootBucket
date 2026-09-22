package com.xncoding.apiversion.controller;

import org.springframework.web.accept.SemanticApiVersionParser.Version;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 版本探测端点：演示版本范围写法与版本参数注入。
 * version = "2+" 表示 2 及以上都路由到这里，v3、v4 不用再建新 controller。
 */
@RestController
public class VersionProbeController {

    @GetMapping(value = "/api/version/echo", version = "2+")
    public Map<String, Object> echo(Version version) {
        return Map.of(
                "resolved", version.toString(),
                "major", version.getMajor(),
                "minor", version.getMinor(),
                "patch", version.getPatch()
        );
    }
}
