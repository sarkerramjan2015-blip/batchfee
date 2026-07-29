package com.batchfee.edu

import com.batchfee.edu.domain.ListLoadState
import com.batchfee.edu.domain.ListLoadStateReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListLoadStateTest {
    @Test
    fun initialEmptyCollectionIsLoadingNotACompletedEmptyState() {
        val state = ListLoadStateReducer.loading(emptyList<String>())

        assertTrue(state is ListLoadState.Loading)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun successfulLoadWithRowsProducesDataAndConsistentCount() {
        val state = ListLoadStateReducer.loaded(listOf("A", "B"))

        assertTrue(state is ListLoadState.Data)
        assertEquals(2, state.items.size)
    }

    @Test
    fun successfulEmptyLoadProducesOnlyTheCompletedEmptyState() {
        val state = ListLoadStateReducer.loaded(emptyList<String>())

        assertEquals(ListLoadState.Empty, state)
    }

    @Test
    fun errorIsNotConvertedIntoAnEmptyState() {
        val state = ListLoadStateReducer.error(emptyList<String>(), "Could not refresh")

        assertTrue(state is ListLoadState.Error)
        assertEquals("Could not refresh", (state as ListLoadState.Error).message)
    }

    @Test
    fun refreshKeepsExistingRowsVisible() {
        val state = ListLoadStateReducer.loading(listOf("Cached student"))

        assertTrue(state is ListLoadState.Loading)
        assertEquals(listOf("Cached student"), state.items)
    }

    @Test
    fun temporaryEmptyBootstrapEmissionStaysLoadingUntilLoadCompletes() {
        val beforeRefreshCompletes = ListLoadStateReducer.loading(emptyList<String>())

        assertTrue(beforeRefreshCompletes is ListLoadState.Loading)
        assertTrue(beforeRefreshCompletes !is ListLoadState.Empty)
    }

    @Test
    fun visibleCountAlwaysUsesTheSameRowsAsTheList() {
        val state = ListLoadStateReducer.loaded(listOf("One", "Two", "Three"))

        assertEquals(3, state.items.size)
        assertEquals(listOf("One", "Two", "Three"), state.items)
    }
}
