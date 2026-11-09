# Audito Amplificus: Smart Sound

A sound enhancer that learns your taste, on your device.

Automatic sound tuning for what you are already listening to. It measures the music, video or talk that is playing,
works out what kind of sound it is, and adjusts the tone for the earbuds or speaker you are using. There are two apps
that share one tuning "brain":

- **Phone (Android 9 or newer)** - tunes the whole phone's sound, keeps a weekly listening-level estimate after the
  WHO / ITU safe-listening guidance, makes 8D copies of songs you own, and learns what you like.
- **Laptop (Windows 10/11)** - tunes the whole laptop's sound through Equalizer APO, and can make karaoke / music-only
  copies, 8D copies and cleaned-up copies of talks from files you pick.

**Private by design.** The phone app has no internet permission, never opens the microphone, and never records or
saves sound. The laptop app listens only to what the laptop itself is playing (loopback), never to a microphone, and
sends nothing anywhere. Details: [PRIVACY.md](PRIVACY.md).

**Not a medical device.** The listening-level figures are a general guide, not a hearing test and not medical advice.
See [DISCLAIMER.md](DISCLAIMER.md).

## Licence

Free noncommercial use and private improvements are permitted for newly licensed original work.
Share the official links freely. Redistributing copies, publishing modified versions, public mirrors,
rebranding, and commercial use require Arnav Agarwal's prior written permission.
Keep authorship and required notices. This is a source-available custom licence, not an open-source licence.
Earlier licence grants and third-party rights remain in force; see [LICENSE-HISTORY.md](LICENSE-HISTORY.md).
Previously published source, embedded app notices, tags and downloadable binaries retain their earlier grants;
this policy does not retroactively prohibit their previously permitted use or distribution.

Full terms: [LICENSE](LICENSE). Permission requests: [GitHub issues](https://github.com/Arnav-Agarwal137/audito-amplificus/issues).

**Exception:** `windows/apo-setup/` remains GPL-2.0-or-later under its own COPYING and NOTICE.md.

## Build the phone app

You need JDK 21 and the Android SDK (compile SDK 37).

```
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :core:test                # the shared tuning brain's tests
```

A release build is signed only if you point it at your own signing file: `-PsoundSigning=<path to a .properties file>`
with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. No key is included in this repository.

## Build the laptop app

You need JDK 21, the .NET 8 SDK, and [Equalizer APO](https://sourceforge.net/projects/equalizerapo/) installed on the
output device you listen on. Pick one folder for the laptop's tools and tell the app about it with the environment
variable `AUDITO_TOOLS` (or `-PauditoTools=<folder>` when building). If you set nothing it uses `D:/dev`.

```
dotnet build windows/helper -c Release -o <tools>/sound-builds/helper
dotnet build windows/apo-setup -c Release -o <tools>/sound-builds/apo-setup
./gradlew :desktop:run -PauditoTools=<tools>
```

The tuning works with just those. The three file tools are optional and need things you install yourself:

| Tool | Needs, inside `<tools>` |
|---|---|
| 8D copy | `demucs-venv` (Python 3.11 + numpy + scipy) and the MIT KEMAR compact set unpacked in `hrtf/` |
| Karaoke / music only | `demucs-venv` with [Demucs](https://github.com/facebookresearch/demucs) and PyTorch; `ffmpeg` on PATH |
| Clean a talk | `denoise-venv` with [DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) and PyTorch; `ffmpeg` on PATH |

**Model weights are not included and must never be redistributed with this app.** Demucs and DeepFilterNet download
their own weights on first use, under their own terms (see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)). The
Demucs weights are for research only, so a build made for sale must leave karaoke and clean-a-talk out:
`-PauditoEdition=paid` hides both and makes them refuse to run.

## Removing it

- **Phone:** uninstall the app; everything it stored goes with it.
- **Laptop:** uninstall "Audito Amplificus" from Windows' Installed apps. That removes the app. Three things stay
  until you remove them, because they live outside the app's folder:
  1. **Equalizer APO** - uninstall it from Installed apps too (it switches its effect off on every output).
  2. **The sound-setup task** - if you pressed "Set up the sound part", a task called by ApoSetup switches Equalizer
     APO on for new headphones. Before uninstalling, run `portable\apo-setup\ApoSetup.exe undo` and then
     `ApoSetup.exe task-remove` from an administrator terminal in the app's folder, then delete the folder
     `C:\Program Files\Audito Amplificus` (the task's own copy of ApoSetup).
  3. **Your data** - delete `%LOCALAPPDATA%\SoundApp` and the registry key
     `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\com\arnavagarwal\sound`.

The licences, privacy policy and these terms are also installed with the laptop app, in its `legal` folder (Start
menu > "Audito Amplificus - licences and privacy").

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Questions, bugs and licence requests: open an issue on GitHub.

Audito Amplificus is an independent project. It is not connected to any music service, earbud or laptop maker, or to
any book, film or game.
