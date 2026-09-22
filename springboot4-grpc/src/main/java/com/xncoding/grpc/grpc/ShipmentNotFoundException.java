package com.xncoding.grpc.grpc;

/** 运单不存在，由 @GrpcAdvice 映射成 Status.NOT_FOUND */
public class ShipmentNotFoundException extends RuntimeException {

    private final String shipmentId;

    public ShipmentNotFoundException(String shipmentId) {
        super("shipment " + shipmentId + " not found");
        this.shipmentId = shipmentId;
    }

    public String getShipmentId() {
        return shipmentId;
    }
}
