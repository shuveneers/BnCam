package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class LensIndicatorStyle(val persistedValue: String, val displayName: String) {
    FLOATING("floating", "Floating"),
    LIST("popup_list", "List"),
    DIRECT_LIST("list", "Direct list");

    companion object {
        fun parse(raw: String?): LensIndicatorStyle =
            entries.firstOrNull { it.persistedValue.equals(raw, ignoreCase = true) } ?: FLOATING
    }
}

private val lensIndicatorStyleKey = stringPreferencesKey("lens_indicator_style")

val Context.lensIndicatorStyleFlow: Flow<LensIndicatorStyle>
    get() = dataStore.data.map { preferences ->
        LensIndicatorStyle.parse(preferences[lensIndicatorStyleKey])
    }

suspend fun Context.setLensIndicatorStyle(style: LensIndicatorStyle) {
    dataStore.edit { preferences ->
        preferences[lensIndicatorStyleKey] = style.persistedValue
    }
}
