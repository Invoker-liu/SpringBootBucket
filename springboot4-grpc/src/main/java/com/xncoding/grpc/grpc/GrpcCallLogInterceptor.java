package com.xncoding.grpc.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** 全局服务端拦截器：记录每个 RPC 的方法名、终态与耗时 */
@Component
@GlobalServerInterceptor
public class GrpcCallLogInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(GrpcCallLogInterceptor.class);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        long t0 = System.nanoTime();
        String method = call.getMethodDescriptor().getFullMethodName();
        ServerCall<ReqT, RespT> wrapped = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                log.info("GRPC_CALL method={} code={} elapsedMs={}", method, status.getCode(), ms);
                super.close(status, trailers);
            }
        };
        ServerCall.Listener<ReqT> listener = next.startCall(wrapped, headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
        };
    }
}
