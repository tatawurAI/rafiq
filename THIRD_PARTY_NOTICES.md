# Third-party notices

Rafiq is licensed under Apache-2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)). This file lists third-party material that ships inside the app but is not a Gradle dependency.

## Fonts

The Android app bundles the following typefaces. Both families are licensed under the [SIL Open Font License, Version 1.1](https://openfontlicense.org/open-font-license-official-text/).

| Family | Used for | Source |
|---|---|---|
| Archivo | Headings | https://github.com/Omnibus-Type/Archivo |
| IBM Plex Sans | Body text | https://github.com/IBM/plex |
| IBM Plex Mono | Labels and code | https://github.com/IBM/plex |

The full licence text and copyright notice for each family are in the upstream repositories linked above. Each bundled font ships with its original licence file.

## Markdown rendering

The app renders chat messages with the following library. It is a Gradle dependency, listed here because its output is a visible part of the app.

| Library | Used for | Licence | Source |
|---|---|---|---|
| Multiplatform Markdown Renderer | Markdown in chat messages | Apache-2.0 | https://github.com/mikepenz/multiplatform-markdown-renderer |

## Terminal emulator

The Terminal view draws a tmux pane with the following library and the native library it bundles. The library is a Gradle dependency, listed here because it ships native code that is not built from this repository.

| Library | Used for | Licence | Source |
|---|---|---|---|
| ConnectBot termlib | The Terminal view | Apache-2.0 | https://github.com/connectbot/termlib |
| libvterm, bundled in termlib | Parsing terminal output | MIT | https://www.leonerd.org.uk/code/libvterm/ |

## QR pairing

The bridge draws the pairing QR code, and the app reads it, with the following libraries. They are Gradle dependencies, listed here because the bridge's MIT licensed one asks for its notice to travel with it.

| Library | Used for | Licence | Source |
|---|---|---|---|
| QR Code generator library, by Project Nayuki | Drawing the QR code in the terminal | MIT | https://github.com/nayuki/QR-Code-generator |
| ZXing core | Reading the QR code in the app | Apache-2.0 | https://github.com/zxing/zxing |

## Gradle dependencies

Licences for Gradle dependencies are listed by a report task added in a later change. This file does not carry a hand-maintained dependency list, because it would drift from `gradle/libs.versions.toml`.
