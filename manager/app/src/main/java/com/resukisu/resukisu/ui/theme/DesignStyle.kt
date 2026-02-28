package com.resukisu.resukisu.ui.theme

import androidx.compose.runtime.compositionLocalOf

enum class DesignStyle(val value: Int) {
    MaterialExpressive(0),
    Miuix(1);

    companion object {
        fun fromValue(value: Int) = if (value == 1) Miuix else MaterialExpressive
    }
}

val LocalDesignStyle = compositionLocalOf { DesignStyle.MaterialExpressive }
