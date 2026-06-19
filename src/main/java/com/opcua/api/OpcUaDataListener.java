package com.opcua.api;

import com.opcua.model.OpcUaDeviceData;

/**
 * OPC UA 数据回调接口。
 * 当订阅数据到达时触发，由 DataDispatchEngine 多播给所有注册的监听器。
 */
@FunctionalInterface
public interface OpcUaDataListener {

    /**
     * 数据到达回调。
     *
     * @param deviceData 设备级聚合数据
     */
    void onDataReceived(OpcUaDeviceData deviceData);
}
