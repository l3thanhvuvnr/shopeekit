package com.personal.shopeekit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.shopeekit.core.storage.ShopeeConfig
import com.personal.shopeekit.features.price.PriceRepository
import com.personal.shopeekit.features.price.SearchOutcome
import com.personal.shopeekit.features.price.ShopeeSearchRepository
import com.personal.shopeekit.features.price.ShopeeSearchResult
import com.personal.shopeekit.features.price.db.TrackedProduct
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the search panel. */
sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Results(val items: List<ShopeeSearchResult>) : SearchUiState()
    object Empty : SearchUiState()
    data class AuthError(val message: String) : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}

class PriceHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val config = ShopeeConfig(app)
    private val searchRepo = ShopeeSearchRepository(config)
    val priceRepo = PriceRepository(app)

    val products: StateFlow<List<TrackedProduct>> =
        priceRepo.observeActiveProducts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val searchState: StateFlow<SearchUiState> = _searchState

    private var searchJob: Job? = null

    /** Debounced search — cancels any in-flight query before starting the next. */
    fun search(query: String, debounceMs: Long = 300L) {
        val trimmed = query.trim()
        searchJob?.cancel()
        if (trimmed.isEmpty()) {
            _searchState.value = SearchUiState.Idle
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            _searchState.value = SearchUiState.Loading
            _searchState.value = when (val outcome = searchRepo.search(trimmed)) {
                is SearchOutcome.Success -> SearchUiState.Results(outcome.results)
                SearchOutcome.Empty -> SearchUiState.Empty
                is SearchOutcome.AuthError -> SearchUiState.AuthError(outcome.message)
                is SearchOutcome.Error -> SearchUiState.Error(outcome.message)
            }
        }
    }

    suspend fun hasCookie(): Boolean = config.getCookie().isNotBlank()

    suspend fun saveCookie(cookie: String) = config.saveCookie(cookie)

    fun setThreshold(productId: String, threshold: Long) {
        viewModelScope.launch { priceRepo.updateAlertThreshold(productId, threshold) }
    }
}
