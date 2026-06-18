package com.opcua.forward.config;

import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QualityFilterTest {

    @Test
    void dropBadOnlyDefaultsBlocksBadAndPassesUncertain() {
        QualityFilter f = QualityFilter.dropBadOnly();
        assertFalse(f.shouldDrop(Quality.Good));
        assertTrue(f.shouldDrop(Quality.Bad));
        assertFalse(f.shouldDrop(Quality.Uncertain));
    }

    @Test
    void passAllAllowsEverything() {
        QualityFilter f = QualityFilter.passAll();
        assertFalse(f.shouldDrop(Quality.Good));
        assertFalse(f.shouldDrop(Quality.Bad));
        assertFalse(f.shouldDrop(Quality.Uncertain));
    }

    @Test
    void nullQualityTreatedAsUncertain() {
        QualityFilter dropBoth = new QualityFilter();
        dropBoth.setDropBad(true);
        dropBoth.setDropUncertain(true);
        assertTrue(dropBoth.shouldDrop(null));

        QualityFilter dropBadOnly = QualityFilter.dropBadOnly();
        assertFalse(dropBadOnly.shouldDrop(null));
    }
}
