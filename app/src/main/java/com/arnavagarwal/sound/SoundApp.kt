package com.arnavagarwal.sound

import android.app.Application
import com.arnavagarwal.sound.engine.Brain

/** Holds the one [Brain] so the service and the screen talk to the same engine. */
class SoundApp : Application() {
    val brain: Brain by lazy { Brain(this) }
}
