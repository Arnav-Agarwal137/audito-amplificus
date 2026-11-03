# ApoSetup licence

ApoSetup is a port of code from Equalizer APO 1.4.2 (`DeviceAPOInfo.cpp`, Copyright (C) Jonas Thedering),
which is licensed GNU GPL version 2 or (at your option) any later version. So **this folder is licensed
GPL-2.0-or-later** (full text: `COPYING`), NOT under the PolyForm Noncommercial licence of the rest of
Audito Amplificus.

It is a separate program: the app only starts it as its own process (`dotnet ApoSetup.dll setup`) and
shares no code with it. Anyone may use, change, share and sell ApoSetup under the GPL, and must offer its
source with any copy they give out.

Decided 9 Oct 2026 during the night watch (no question to the owner possible): keeping it separate under
the GPL is the only way to ship this port legally; the alternative is a clean-room rewrite.
