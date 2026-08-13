package com.authorss81.noteflow.ui.components

import android.os.Build

object ShaderCapabilityHelper {
    val isAgslSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU // API 33
}
