package com.iptv.tv.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the focused control is inside the page area (below the top bar). Pages that load
 * their content asynchronously read this before requesting focus, so they take it when the
 * remote is stranded on the avatar or nowhere, and leave it alone once it is on the page.
 */
val LocalPageHasFocus = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }
