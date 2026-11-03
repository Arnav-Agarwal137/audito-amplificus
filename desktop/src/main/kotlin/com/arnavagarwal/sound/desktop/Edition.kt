package com.arnavagarwal.sound.desktop

/**
 * Which copy of the laptop app this is: FREE (anyone, non-commercial, PolyForm Noncommercial) or PAID (sold).
 * Set `-Daudito.edition=free|paid` (gradle: -PauditoEdition=paid). Not set = FREE, so his laptop is unchanged.
 *
 * Why it exists (his ask 8 Oct 2026 20:35, research/revenue-check.md): karaoke uses Demucs htdemucs weights, which their
 * author says are "provided only for scientific purposes", and clean-a-talk uses DeepFilterNet3 weights whose licence is
 * not stated. Neither may be part of anything sold. A PAID copy hides both cards AND refuses to run them.
 *
 * Fails toward safe: any value other than "free" (a typo, "Paid", "pay", "commercial") is PAID, because a paid build that
 * quietly showed the research-only tools is the harmful mistake, and a free build missing two cards is the harmless one.
 */
object Edition {
    enum class Kind { FREE, PAID }

    fun parse(value: String?): Kind = if (value == null || value.trim().equals("free", ignoreCase = true)) Kind.FREE else Kind.PAID

    val current: Kind get() = parse(System.getProperty("audito.edition"))

    /** Karaoke (Demucs) and clean a talk (DeepFilterNet): only in a FREE copy. */
    fun researchModelsAllowed(kind: Kind = current): Boolean = kind == Kind.FREE

    const val NOT_IN_PAID = "not part of this copy: its sound model is licensed for research only"
}
