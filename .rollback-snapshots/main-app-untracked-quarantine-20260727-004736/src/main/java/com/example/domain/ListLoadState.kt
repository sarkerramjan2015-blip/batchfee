package com.batchfee.edu.domain

/**
 * A list's visible state. Loading/error retain any known rows so a refresh
 * cannot turn existing content into a false empty screen.
 */
sealed interface ListLoadState<out T> {
    val items: List<T>

    data class Loading<T>(override val items: List<T>) : ListLoadState<T>
    data class Data<T>(override val items: List<T>) : ListLoadState<T>
    data object Empty : ListLoadState<Nothing> {
        override val items: List<Nothing> = emptyList()
    }
    data class Error<T>(
        override val items: List<T>,
        val message: String
    ) : ListLoadState<T>
}

object ListLoadStateReducer {
    fun <T> loading(cachedItems: List<T>): ListLoadState<T> =
        ListLoadState.Loading(cachedItems)

    fun <T> loaded(items: List<T>): ListLoadState<T> =
        if (items.isEmpty()) ListLoadState.Empty else ListLoadState.Data(items)

    fun <T> error(cachedItems: List<T>, message: String): ListLoadState<T> =
        ListLoadState.Error(cachedItems, message)
}
