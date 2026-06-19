package com.opcua.api;

/**
 * 写操作结果。
 *
 * <p>表示一次 OPC UA 节点写入的结果，成功时不携带错误信息，
 * 失败时包含失败原因。</p>
 */
public final class WriteResult {

    private final boolean success;
    private final String nodeId;
    private final String errorMessage;

    private WriteResult(boolean success, String nodeId, String errorMessage) {
        this.success = success;
        this.nodeId = nodeId;
        this.errorMessage = errorMessage;
    }

    public static WriteResult success(String nodeId) {
        return new WriteResult(true, nodeId, null);
    }

    public static WriteResult failure(String nodeId, String errorMessage) {
        return new WriteResult(false, nodeId, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
