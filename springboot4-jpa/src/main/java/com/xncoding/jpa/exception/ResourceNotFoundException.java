package com.xncoding.jpa.exception;

/**
 * 目标资源不存在，映射为 HTTP 404。
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
