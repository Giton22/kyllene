package dev.hermeskotlin.designsystem.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SegmentedControlTest {

    @Test
    fun stepsRightAndLeftInLtr() {
        assertEquals(1, segmentedStepIndex(currentIndex = 0, count = 3, directionRight = true, rtl = false))
        assertEquals(2, segmentedStepIndex(currentIndex = 1, count = 3, directionRight = true, rtl = false))
        assertNull(segmentedStepIndex(currentIndex = 2, count = 3, directionRight = true, rtl = false))
        assertEquals(1, segmentedStepIndex(currentIndex = 2, count = 3, directionRight = false, rtl = false))
        assertEquals(0, segmentedStepIndex(currentIndex = 1, count = 3, directionRight = false, rtl = false))
        assertNull(segmentedStepIndex(currentIndex = 0, count = 3, directionRight = false, rtl = false))
    }

    @Test
    fun reversesDirectionInRtl() {
        // In RTL, option 0 sits on the right and option 2 on the left: Left advances, Right steps back.
        assertEquals(1, segmentedStepIndex(currentIndex = 0, count = 3, directionRight = false, rtl = true))
        assertEquals(2, segmentedStepIndex(currentIndex = 1, count = 3, directionRight = false, rtl = true))
        assertNull(segmentedStepIndex(currentIndex = 2, count = 3, directionRight = false, rtl = true))
        assertEquals(1, segmentedStepIndex(currentIndex = 2, count = 3, directionRight = true, rtl = true))
        assertEquals(0, segmentedStepIndex(currentIndex = 1, count = 3, directionRight = true, rtl = true))
        assertNull(segmentedStepIndex(currentIndex = 0, count = 3, directionRight = true, rtl = true))
    }

    @Test
    fun missingOrSingleSelectionNeverStepsOutOfBounds() {
        assertNull(segmentedStepIndex(currentIndex = -1, count = 3, directionRight = true, rtl = false))
        assertNull(segmentedStepIndex(currentIndex = 0, count = 1, directionRight = true, rtl = false))
        assertNull(segmentedStepIndex(currentIndex = 0, count = 0, directionRight = false, rtl = true))
    }
}
