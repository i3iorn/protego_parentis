package dev.schrammel.protego_parentis.ui

import dev.schrammel.protego_parentis.rules.Feature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure, so — unlike [HomeSectionsTest] — this needs no Robolectric: it takes
 * the per-child `disabledFeatures` sets as data rather than reading them off
 * disk itself, which is the whole point of [ParentHomeActivity.disabledFeaturesByChild]
 * having been split out to read them once instead of [featureVisible] reading
 * them on every call.
 */
class FeatureVisibleTest {

    @Test
    fun `no managed children means every feature shows`() {
        assertTrue(featureVisible(Feature.REQUESTS, emptyList()))
    }

    @Test
    fun `a child this phone has not heard from yet counts as having it on`() {
        assertTrue(featureVisible(Feature.REQUESTS, listOf(null)))
    }

    @Test
    fun `one child still using a feature keeps its row, even if another turned it off`() {
        val stillOn: Set<String>? = emptySet()
        val turnedOff: Set<String>? = setOf(Feature.REQUESTS.name)
        assertTrue(featureVisible(Feature.REQUESTS, listOf(turnedOff, stillOn)))
    }

    @Test
    fun `every child turning a feature off hides its row`() {
        val disabled: Set<String>? = setOf(Feature.REQUESTS.name)
        assertFalse(featureVisible(Feature.REQUESTS, listOf(disabled, disabled)))
    }
}
