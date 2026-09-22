package com.xncoding.multisource.exception;

/**
 * 查不到资源，映射成 404。
 *
 * <p>带上资源类型和 id，异常处理器会把它放进 {@code problem+json} 的扩展字段里，
 * 和前面几篇的工程保持一致。
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;

    private final Object resourceId;

    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(resourceType + " " + resourceId + " 不存在");
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
