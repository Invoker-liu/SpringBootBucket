package com.xncoding.mongo.exception;

/**
 * 目标资源不存在，映射为 HTTP 404。
 * <p>
 * {@code resourceId} 的类型是 {@link Object}：前三篇是 {@code Long}，
 * 这一篇是 MongoDB 的 ObjectId 字符串。异常本身不用关心。
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;

    private final Object resourceId;

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super("%s不存在：%s".formatted(resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Object getResourceId() {
        return resourceId;
    }
}
