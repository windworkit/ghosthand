package com.ghosthand.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectiveMechanicCatalogTest {

    @Test
    fun mapsOnlyDocumentedObjectiveMechanics() {
        assertEquals(
            ObjectiveMechanic.COLLECT_MARKED_TILE_ON_LINE_CLEAR,
            ObjectiveMechanicCatalog.forType(ObjectiveType.HEART)
        )
        assertEquals(
            ObjectiveMechanic.BALLOON_BLOCK_REQUIRES_REPEATED_RELEASES,
            ObjectiveMechanicCatalog.forType(ObjectiveType.BALLOON)
        )
        assertEquals(
            ObjectiveMechanic.APPLE_MUST_FALL_TO_BOTTOM,
            ObjectiveMechanicCatalog.forType(ObjectiveType.APPLE)
        )
        assertEquals(
            ObjectiveMechanic.REACH_SCORE_TARGET,
            ObjectiveMechanicCatalog.forType(ObjectiveType.SCORE)
        )
    }

    @Test
    fun leavesUnverifiedObjectiveTypesUnknown() {
        assertEquals(ObjectiveMechanic.UNKNOWN, ObjectiveMechanicCatalog.forType(ObjectiveType.FLOWER))
        assertEquals(ObjectiveMechanic.UNKNOWN, ObjectiveMechanicCatalog.forType(ObjectiveType.BOMB))
        assertEquals(ObjectiveMechanic.UNKNOWN, ObjectiveMechanicCatalog.forType(ObjectiveType.FIREWORK))
        assertEquals(ObjectiveMechanic.UNKNOWN, ObjectiveMechanicCatalog.forType(ObjectiveType.GEM))
        assertEquals(ObjectiveMechanic.UNKNOWN, ObjectiveMechanicCatalog.forType(ObjectiveType.CRATE))
    }
}
