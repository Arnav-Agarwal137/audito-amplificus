package com.arnavagarwal.sound.engine

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the sound is coming out of. Profiles are per output: earbuds A, earbuds B, the phone speaker. */
data class Output(val kind: Kind, val name: String, val deviceType: Int = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
    enum class Kind { BLUETOOTH, WIRED, USB, SPEAKER, OTHER }
    val isHeadphones: Boolean get() = kind == Kind.BLUETOOTH || kind == Kind.WIRED || kind == Kind.USB
    /** Key under which this output's profile (earbud correction, dose calibration) is stored. */
    val key: String get() = "${kind.name}:${name.trim().lowercase()}"

    companion object {
        /** Android gave no name for these earbuds: every such pair would share one profile. */
        const val BLUETOOTH_UNNAMED = "Bluetooth"
    }
}

class OutputWatcher(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val _output = MutableStateFlow(current())
    val output: StateFlow<Output> = _output

    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { _output.value = current() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { _output.value = current() }
    }

    fun start() = am.registerAudioDeviceCallback(cb, null)
    fun stop() = am.unregisterAudioDeviceCallback(cb)

    /**
     * Android routes media to the most recently connected headset; pick in the same priority:
     * Bluetooth A2DP / LE audio > wired > USB > speaker.
     */
    private fun current(): Output {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun find(vararg types: Int) = outs.firstOrNull { it.type in types }
        find(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)?.let {
            return Output(Output.Kind.BLUETOOTH, it.productName?.toString()?.ifBlank { null } ?: Output.BLUETOOTH_UNNAMED, it.type)
        }
        find(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET)?.let { return Output(Output.Kind.WIRED, "Wired headphones", it.type) }
        find(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)?.let {
            return Output(Output.Kind.USB, it.productName?.toString()?.ifBlank { null } ?: "USB audio", it.type)
        }
        return Output(Output.Kind.SPEAKER, "Phone speaker")
    }
}

/** Media volume: current step, the dB attenuation of every step, and change notifications. */
class VolumeReader(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    val stream = AudioManager.STREAM_MUSIC
    val index: Int get() = am.getStreamVolume(stream)
    val maxIndex: Int get() = am.getStreamMaxVolume(stream)

    /** dB attenuation for each step on output [device] (an AudioDeviceInfo type; API 28+). Step 0 is mute. */
    fun dbByIndex(device: Int): DoubleArray {
        val max = maxIndex
        return DoubleArray(max + 1) { i ->
            if (i == 0) -96.0 else runCatching { am.getStreamVolumeDb(stream, i, device).toDouble() }.getOrElse { -60.0 * (1 - i.toDouble() / max) }
                .let { if (it.isNaN() || it.isInfinite()) -96.0 else it.coerceIn(-96.0, 0.0) }
        }
    }

    fun set(i: Int) = am.setStreamVolume(stream, i.coerceIn(0, maxIndex), AudioManager.FLAG_SHOW_UI)
}
