package com.opcua.forward.engine;

import com.opcua.forward.config.QualityFilter;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QualityFilterApplierTest {

    @Test
    void dropBadOnlyRemovesBadPointsKeepsGoodAndUncertain() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Good),
            point("n2", Quality.Bad),
            point("n3", Quality.Uncertain)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, QualityFilter.dropBadOnly());

        assertNotNull(out);
        assertEquals(2, out.getData().size());
        assertEquals("n1", out.getData().get(0).getNodeId());
        assertEquals("n3", out.getData().get(1).getNodeId());
    }

    @Test
    void allBadReturnsNullWhenDropBad() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Bad),
            point("n2", Quality.Bad)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, QualityFilter.dropBadOnly());

        assertNull(out, "all-Bad batch with dropBad should yield null (caller skips enqueue)");
    }

    @Test
    void passAllReturnsOriginalReferenceUnchanged() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Good),
            point("n2", Quality.Bad)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, QualityFilter.passAll());

        assertSame(data, out);
    }

    @Test
    void nullFilterTreatedAsDropBadOnly() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Good),
            point("n2", Quality.Bad)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, null);

        assertNotNull(out);
        assertEquals(1, out.getData().size());
        assertEquals("n1", out.getData().get(0).getNodeId());
    }

    private OpcUaDeviceData makeData(List<OpcUaDataPoint> points) {
        return new OpcUaDeviceData(Instant.now(),
                new OpcUaDeviceData.SourceInfo("p", "d", "x"), points);
    }

    private OpcUaDataPoint point(String nodeId, Quality q) {
        return new OpcUaDataPoint(nodeId, nodeId, "v", "Double",
            q, true, q.name(), Instant.now(), Instant.now());
    }
}