package com.xin.flaremusic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Screen-level state for the Compose UI.
 * Media playback remains owned by Media3 and FlarePlaybackService.
 */
class FlareUiViewModel : ViewModel() {
    var selectedTab by mutableStateOf("Home")
        private set

    fun selectTab(tab: String) {
        if (tab in TOP_LEVEL_TABS) selectedTab = tab
    }

    private companion object {
        val TOP_LEVEL_TABS = setOf("Home", "Search", "Library", "Settings")
    }
}
