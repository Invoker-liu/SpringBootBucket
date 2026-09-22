package com.xncoding.grpc.config;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** 故障注入开关：normal 正常，slow 让服务端 RPC 变慢以触发客户端 deadline */
@Component
public class FaultControl {

    public enum Mode { NORMAL, SLOW }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.NORMAL);

    public void set(String value) {
        mode.set("slow".equalsIgnoreCase(value) ? Mode.SLOW : Mode.NORMAL);
    }

    public boolean isSlow() {
        return mode.get() == Mode.SLOW;
    }

    public String current() {
        return mode.get().name().toLowerCase(Locale.ROOT);
    }
}
