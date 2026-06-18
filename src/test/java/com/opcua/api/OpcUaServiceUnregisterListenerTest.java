package com.opcua.api;

import com.opcua.core.ConnectionManager;
import com.opcua.core.DataDispatchEngine;
import com.opcua.core.ReadWriteHandler;
import com.opcua.core.SubscriptionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpcUaServiceUnregisterListenerTest {

    @Test
    void unregisterListener_delegatesToDispatchEngine() {
        DataDispatchEngine dispatch = mock(DataDispatchEngine.class);
        when(dispatch.removeListener(any())).thenReturn(true);

        OpcUaService svc = new OpcUaService(
                mock(ConnectionManager.class), dispatch,
                mock(SubscriptionManager.class), mock(ReadWriteHandler.class));

        OpcUaDataListener listener = data -> { };
        boolean removed = svc.unregisterListener(listener);

        assertTrue(removed);
        verify(dispatch).removeListener(listener);
    }

    @Test
    void unregisterListener_returnsFalseWhenNotRegistered() {
        DataDispatchEngine dispatch = mock(DataDispatchEngine.class);
        when(dispatch.removeListener(any())).thenReturn(false);

        OpcUaService svc = new OpcUaService(
                mock(ConnectionManager.class), dispatch,
                mock(SubscriptionManager.class), mock(ReadWriteHandler.class));

        assertFalse(svc.unregisterListener(data -> { }));
    }
}
