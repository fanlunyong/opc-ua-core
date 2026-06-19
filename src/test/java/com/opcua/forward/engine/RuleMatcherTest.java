package com.opcua.forward.engine;

import com.opcua.forward.config.MatchCondition;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleMatcherTest {

    @Test
    void emptyMatch_matchesAll() {
        assertTrue(RuleMatcher.matches(new MatchCondition(), data("p1", "d1", "n1")));
    }

    @Test
    void productId_mismatch_rejects() {
        MatchCondition m = new MatchCondition(); m.setProductId("line-A");
        assertFalse(RuleMatcher.matches(m, data("line-B", "d1", "n1")));
    }

    @Test
    void productId_match_passes() {
        MatchCondition m = new MatchCondition(); m.setProductId("line-A");
        assertTrue(RuleMatcher.matches(m, data("line-A", "d1", "n1")));
    }

    @Test
    void deviceId_match() {
        MatchCondition m = new MatchCondition();
        m.setProductId("line-A"); m.setDeviceId("furnace-1");
        assertTrue(RuleMatcher.matches(m, data("line-A", "furnace-1", "n1")));
        assertFalse(RuleMatcher.matches(m, data("line-A", "furnace-2", "n1")));
    }

    @Test
    void nodeId_match_anyDataPointMatches() {
        MatchCondition m = new MatchCondition(); m.setNodeId("ns=2;s=Temp");
        OpcUaDeviceData d = new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", "d1", "x"),
                List.of(point("ns=2;s=Press"), point("ns=2;s=Temp")));
        assertTrue(RuleMatcher.matches(m, d));
    }

    @Test
    void nodeId_match_allMissingRejects() {
        MatchCondition m = new MatchCondition(); m.setNodeId("ns=2;s=Temp");
        OpcUaDeviceData d = new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", "d1", "x"),
                List.of(point("ns=2;s=Press")));
        assertFalse(RuleMatcher.matches(m, d));
    }

    private OpcUaDeviceData data(String pid, String did, String nid) {
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(pid, did, "x"),
                List.of(point(nid)));
    }

    private OpcUaDataPoint point(String nodeId) {
        return new OpcUaDataPoint(nodeId, "name", 1, "Int32",
                Quality.Good, true, "Good", Instant.now(), Instant.now());
    }
}