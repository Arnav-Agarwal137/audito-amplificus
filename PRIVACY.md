# Privacy policy - Audito Amplificus

Effective 8 October 2026, updated 2 November 2026 (laptop headphone fix; the one Equalizer APO download). Applies to the Android app and the Windows app published by Arnav Agarwal.

## The short version

**Neither app collects, sends or sells any data. There is no account, no analytics, no advertising and no tracking.**
Everything stays on your own device.

## Phone app (Android)

- **No internet.** The app does not ask Android for the internet permission, so it cannot send anything anywhere.
- **No cloud backup.** Android's automatic backup is switched off for this app, so its settings are not copied to
  your Google account. (Moving to a new phone by cable or direct transfer may still carry them across; that never
  passes through us.)
- **Microphone: never used.** Android lists the "record audio" permission because its Visualizer - the part that
  measures the sound already playing on the phone - requires it. The app uses it only for that measurement of the
  phone's own output, never opens the microphone, and never records or saves any sound.
- **Notification access** is used to read the name of the song or video that is playing and which app is playing it,
  so the tuning can match it.
- **What is stored, only on the phone:** your settings, your earbud correction file if you add one, the tuning it has
  learned from your choices (with the name of the song you adjusted, so it is not counted twice), the names of the
  apps it has seen playing sound, and a weekly listening-level estimate. Uninstalling the app deletes all of it.
- **Files you pick** (for an 8D copy) are read on the phone and the new copy is saved in the phone's Music folder.
  Nothing is uploaded.
- **Private browsing:** sound from a private / incognito browser tab is not tuned, measured or remembered.

## Laptop app (Windows)

- **Nothing of yours is sent anywhere.** The app itself contains no network code. The one exception is a download,
  not an upload: in the setup copy, pressing "Set up the sound part" fetches the official Equalizer APO installer from
  its own SourceForge page (as any download does, SourceForge sees that a download happened, from your internet
  address). Nothing about you, your music or your settings is sent.
- **No microphone.** It measures what the laptop is playing through Windows' loopback capture of the output device.
- **What is stored, only on the laptop:** your settings, a song log (song title, playing app, the switches you had on
  and measurements - numbers, not sound) used for the tuning, any headphone correction files you add (in
  `%LOCALAPPDATA%\SoundApp\headphone-fixes`), and the Equalizer APO configuration file it writes. Settings are kept in the
  Windows registry under `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\arnavagarwal\sound`. Uninstalling removes the
  app's own folder; the `%LOCALAPPDATA%\SoundApp` folder and that registry key stay until you delete them, and
  Equalizer APO stays installed until you remove it from Windows' "Installed apps" (see the README, "Removing it").
- **Files you pick** (karaoke, 8D copy, clean a talk) are processed on the laptop and the results saved next to them.
  The optional model downloads (Demucs, DeepFilterNet) are made by those tools from their own publishers on first use,
  under those publishers' terms; they upload nothing of yours.

## Children

The apps are not designed for children and are not directed at them.

## Changes and contact

If this policy changes, the new version will be published here with a new date. Questions: open an issue on the
project's GitHub page (github.com/Arnav-Agarwal137).
