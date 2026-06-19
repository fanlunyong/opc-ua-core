package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionFingerprintTest {

    @Test
    void kafka_sameBootstrapAndSecurity_equalFingerprint() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("kafka"); t1.setBootstrapServers("k:9092"); t1.setSecurityProtocol("PLAINTEXT");
        t1.setTopic("topic-A");

        ForwardTarget t2 = new ForwardTarget();
        t2.setType("kafka"); t2.setBootstrapServers("k:9092"); t2.setSecurityProtocol("PLAINTEXT");
        t2.setTopic("topic-B");

        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
    }

    @Test
    void kafka_differentBootstrap_differentFingerprint() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("kafka"); t1.setBootstrapServers("k1:9092");
        ForwardTarget t2 = new ForwardTarget();
        t2.setType("kafka"); t2.setBootstrapServers("k2:9092");

        assertNotEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
    }

    @Test
    void mqtt_byBrokerUrlAndClientId() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("mqtt"); t1.setBrokerUrl("tcp://b:1883"); t1.setClientId("c1");
        ForwardTarget t2 = new ForwardTarget();
        t2.setType("mqtt"); t2.setBrokerUrl("tcp://b:1883"); t2.setClientId("c1");
        ForwardTarget t3 = new ForwardTarget();
        t3.setType("mqtt"); t3.setBrokerUrl("tcp://b:1883"); t3.setClientId("c2");

        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
        assertNotEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t3));
    }

    @Test
    void influxdb_byUrlOrgToken() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("influxdb"); t1.setUrl("http://i:8086"); t1.setOrg("o"); t1.setToken("tok");
        ForwardTarget t2 = new ForwardTarget();
        t2.setType("influxdb"); t2.setUrl("http://i:8086"); t2.setOrg("o"); t2.setToken("tok");
        ForwardTarget t3 = new ForwardTarget();
        t3.setType("influxdb"); t3.setUrl("http://i:8086"); t3.setOrg("o"); t3.setToken("DIFFERENT");

        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
        assertNotEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t3));
    }

    @Test
    void http_singletonByType() {
        ForwardTarget t1 = new ForwardTarget(); t1.setType("http"); t1.setUrl("http://a");
        ForwardTarget t2 = new ForwardTarget(); t2.setType("http"); t2.setUrl("http://b");
        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
    }

    @Test
    void unknownType_throws() {
        ForwardTarget t = new ForwardTarget(); t.setType("unknown");
        assertThrows(IllegalArgumentException.class, () -> ConnectionFingerprint.of(t));
    }
}
