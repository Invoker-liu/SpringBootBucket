package com.xncoding.grpc.grpc;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.advice.GrpcExceptionHandler;

/** 业务异常到 gRPC Status 的映射，Boot 自动包成全局拦截器 */
@GrpcAdvice
public class GrpcExceptionAdvice {

    @GrpcExceptionHandler(ShipmentNotFoundException.class)
    public StatusException handleNotFound(ShipmentNotFoundException e) {
        Metadata trailers = new Metadata();
        Metadata.Key<String> key = Metadata.Key.of("shipment-id", Metadata.ASCII_STRING_MARSHALLER);
        trailers.put(key, e.getShipmentId());
        return Status.NOT_FOUND.withDescription(e.getMessage()).asException(trailers);
    }
}
