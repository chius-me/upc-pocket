package me.chius.upcpocket.feature.calendar

import java.io.IOException

data class CalendarArticleSummary(
    val title: String,
    val pageUrl: String,
    val publishDate: String? = null,
)

data class CalendarUiState(
    val articles: List<CalendarArticleSummary> = emptyList(),
    val selectedArticle: CalendarArticleSummary? = null,
    val imageUrls: List<String> = emptyList(),
    val cachedImagePaths: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class CalendarException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
