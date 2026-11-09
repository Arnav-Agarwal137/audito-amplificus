# Third-party notices - Audito Amplificus

Audito Amplificus itself is Copyright (c) 2026 Arnav Agarwal. Newly licensed original work uses the root LICENSE;
earlier PolyForm grants remain in force (see LICENSE-HISTORY.md). It uses or works with the following, each
under its own terms. Full texts and in-app credits are described below.
Full texts are in the `licenses/` folder, inside the phone app
(Settings > Privacy, safety and credits > Read the licences), and in the `legal` folder of the installed laptop app
(Start menu > "Audito Amplificus - licences and privacy").

## Shipped inside the phone app

| What | Author | Licence |
|---|---|---|
| Inter Tight font | The Inter Project Authors | SIL Open Font License 1.1 (`licenses/InterTight-OFL.txt`) |
| Demo song "Beach Babes" | SUPERARE | CC0 1.0, public domain (`licenses/demo-song.txt`) |
| MIT KEMAR HRTF measurements (in the 8D kit) | Bill Gardner and Keith Martin, MIT Media Lab | Free with no restrictions on use, provided the authors are cited (`licenses/MIT-KEMAR.txt`) |
| Kotlin, Jetpack Compose, AndroidX, kotlinx libraries | JetBrains, Google, contributors | Apache License 2.0 |

## Used by the laptop app

| What | Licence | Shipped? |
|---|---|---|
| Compose Multiplatform, Skiko, Kotlin, kotlinx, AndroidX lifecycle/savedstate, JetBrains annotations, JSpecify | Apache License 2.0 (`licenses/laptop/apache-2.0.txt`) | Yes, as libraries |
| Skia graphics library (inside Skiko), Copyright (c) 2011 Google Inc. | BSD 3-Clause (`licenses/laptop/skia-BSD-3.txt`) | Yes, inside Skiko |
| ICU data (`icudtl.dat`, inside Skiko), Unicode, Inc. | Unicode License V3 (`licenses/laptop/icu-unicode.txt`) | Yes |
| Inter Tight font | SIL Open Font License 1.1 (`licenses/laptop/InterTight-OFL.txt`) | Yes |
| Java runtime (OpenJDK 21, trimmed by jpackage) | GPL-2.0 with the Classpath Exception | Yes, in the setup copy; its own notices are in the app's `runtime/legal` folder |
| NAudio.Core and NAudio.Wasapi 2.2.1 (the Windows helper's loopback capture), Copyright 2020 Mark Heath | MIT (`licenses/laptop/naudio-MIT.txt`) | Yes, as NuGet libraries of the helper |
| .NET 8 runtime and System.ServiceProcess.ServiceController, .NET Foundation and Contributors / Microsoft | MIT (`licenses/laptop/dotnet-runtime-MIT.txt`, with `licenses/laptop/dotnet-runtime-THIRD-PARTY-NOTICES.txt`) | Yes, in the setup copy (the helper and ApoSetup are self-contained) |
| Windows SDK .NET projection (`Microsoft.Windows.SDK.NET.dll`, `WinRT.Runtime.dll`), Microsoft | Windows SDK licence (aka.ms/WinSDKLicenseURL) / MIT | Yes, as libraries of the helper |
| Inno Setup (makes the one-file setup) | Inno Setup licence | Used to build the setup; not part of the installed app |
| MIT KEMAR HRTF measurements (8D copy) | as above | No - the user downloads them |
| Equalizer APO | GPL-2.0 | **No.** A separate program. The setup copy downloads the official 1.4.2 installer from its own SourceForge page (checked against its published SHA-256) only when you press "Set up the sound part"; the app then only writes its text configuration file |
| ApoSetup (`windows/apo-setup/`) - code ported from Equalizer APO 1.4.2 `DeviceAPOInfo.cpp`, Copyright (C) Jonas Thedering | **GPL-2.0-or-later** (its own `COPYING`), NOT PolyForm | Yes, as a separate program with its full source; the app only starts it as its own process (switches Equalizer APO on for every output, with one Windows permission prompt) |
| ffmpeg | LGPL / GPL depending on the build | **No.** The user installs it |
| Demucs (karaoke) - code | MIT | No - the user installs it |
| Demucs `htdemucs` model weights | **Not MIT.** The author states they "are provided only for scientific purposes" (github.com/facebookresearch/demucs/issues/327) | **No, and must never be bundled.** Downloaded by Demucs itself on first use. Not allowed in any paid build (`-PauditoEdition=paid` removes karaoke) |
| DeepFilterNet (clean a talk) - code | MIT / Apache 2.0 | No - the user installs it |
| DeepFilterNet3 model weights | **Licence not stated** by the publisher | **No, and must never be bundled.** Downloaded by DeepFilterNet on first use. Not allowed in any paid build (`-PauditoEdition=paid` removes clean a talk) |
| PyTorch, torchaudio, numpy, scipy | BSD-style | No - the user installs them |

## Referenced, not included

- **AutoEq** earbud / headphone correction files (Jaakko Pasanen, MIT): the user downloads a file from autoeq.app and
  adds it, in the phone app or in the laptop app's "headphone fix" card. Nothing from AutoEq is included, and the
  correction is only as good as that measurement.
- **WHO / ITU-T H.870** safe-listening guidance: followed as a reference for the listening-level estimate.

If you believe something here is credited wrongly or missing, please open an issue.
