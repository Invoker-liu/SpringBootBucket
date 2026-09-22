package com.xncoding.grpc.config;

import com.xncoding.grpc.shipment.ShipmentServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

/** gRPC 客户端装配：通道名 logistics 的 target 由 spring.grpc.client.channel.logistics.target 提供 */
@Configuration
public class GrpcClientConfig {

    @Bean
    ShipmentServiceGrpc.ShipmentServiceBlockingStub shipmentStub(GrpcChannelFactory channels) {
        return ShipmentServiceGrpc.newBlockingStub(channels.createChannel("logistics"));
    }
}
