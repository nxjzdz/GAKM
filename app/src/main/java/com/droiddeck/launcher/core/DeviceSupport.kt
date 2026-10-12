package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import java.io.File

/** Whether this device has a graphics backend the runtime can use. Adreno uses Turnip;
 * PowerVR uses its vendor Vulkan driver through the bundled runtime bridge. Other GPU families
 * still need a supported backend before a session can display frames. */
object DeviceSupport {
    fun adreno(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

    fun graphicsAvailable(): Boolean = graphicsAvailable(adreno(), SystemVulkanDriver.isAvailable())

    internal fun graphicsAvailable(adreno: Boolean, powerVrReady: Boolean): Boolean = adreno || powerVrReady

    /** The chip as the device names it, for the card that explains the refusal. */
    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }

    /** False on the Odin 3 (CQ8725S): a low-latency Wi-Fi lock makes its WLAN firmware hang, and
     *  that firmware reboots the whole device instead of restarting Wi-Fi. */
    fun lowLatencyWifiSafe(): Boolean = Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "CQ8725S"
}
