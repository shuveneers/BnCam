package com.bncam.ui.screens.settings.lens_profiles

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** Shared state contract for DataStore-backed settings: optimistic UI, flow confirmation, rollback. */
class OptimisticPersistedBinding<T> internal constructor(
    val value: T,
    val pending: Boolean,
    val update: (T) -> Unit
)

@Composable
fun <T> rememberOptimisticPersistedBinding(
    stableKey: String,
    authoritativeValue: T,
    persist: suspend (T) -> Unit
): OptimisticPersistedBinding<T> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var optimisticValue by remember(stableKey) { mutableStateOf<T?>(null) }
    var hasOptimisticValue by remember(stableKey) { mutableStateOf(false) }

    LaunchedEffect(stableKey, authoritativeValue, hasOptimisticValue, optimisticValue) {
        if (hasOptimisticValue && optimisticValue == authoritativeValue) {
            hasOptimisticValue = false
            optimisticValue = null
        }
    }

    @Suppress("UNCHECKED_CAST")
    val visibleValue = if (hasOptimisticValue) optimisticValue as T else authoritativeValue
    return OptimisticPersistedBinding(
        value = visibleValue,
        pending = hasOptimisticValue,
        update = { next ->
            optimisticValue = next
            hasOptimisticValue = true
            scope.launch {
                try {
                    persist(next)
                } catch (failure: Throwable) {
                    hasOptimisticValue = false
                    optimisticValue = null
                    Toast.makeText(
                        context,
                        "Could not save setting: ${failure.message ?: failure.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    )
}
