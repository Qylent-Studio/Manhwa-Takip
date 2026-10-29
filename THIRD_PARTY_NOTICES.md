# Public HTTPS transport dependencies

- **OkHttp 4.12.1** (`com.squareup.okhttp3:okhttp`), Square, Inc. — Apache License 2.0. https://github.com/square/okhttp
- **Okio 3.6.0** (`com.squareup.okio:okio`, JVM artifact `okio-jvm`), Square, Inc. — Apache License 2.0. https://github.com/square/okio

License: https://www.apache.org/licenses/LICENSE-2.0

These are the libraries introduced for the guarded public HTTPS transport. Python uses its standard library.

## Desktop WebP decoding

- **Pillow 13.0.0**, Jeffrey 'Alex' Clark and contributors — MIT-CMU. https://python-pillow.github.io/
- Pinned runtime requirement: `companion/requirements-runtime.txt`. The verified Windows wheel's complete license bundle, including bundled codec notices (libwebp and others), is preserved verbatim in `companion/licenses/Pillow-LICENSE.txt`. Distributions must retain this bundle and applicable notices. No runtime automatic dependency installation is performed.

## Windows notification-area adapter

- **pystray 0.19.5**, Moses Palmér — LGPL-3.0-or-later. Unmodified, dynamically imported Python dependency. https://github.com/moses-palmer/pystray/tree/v0.19.5 and https://pypi.org/project/pystray/0.19.5/ (source distribution available there).
- Complete wheel license texts are retained in `companion/licenses/pystray-COPYING.txt` (GPLv3) and `companion/licenses/pystray-COPYING.LGPL.txt` (LGPLv3 additional permissions).
- **six 1.17.0**, Benjamin Peterson — MIT. https://github.com/benjaminp/six ; complete wheel license: `companion/licenses/six-LICENSE.txt`.
- Exact requirements: `companion/requirements-runtime.txt`. Development installation is explicit; neither application nor installer downloads/installs dependencies automatically. Installer preflight checks the selected Python runtime and copies the license allowlist without touching user data. Release packaging must retain notices, provide LGPL corresponding source and replacement/relinking rights for pystray, and verify complete packaged-runtime licenses in Task 10. This source-stage work does not claim packaged LGPL compliance.

## Embedded Windows runtime (0.10.0)

- **CPython 3.13.15** x64, Python Software Foundation — PSF License and bundled third-party notices: `runtime/LICENSE.txt`. Official embedded archive: https://www.python.org/ftp/python/3.13.15/python-3.13.15-embed-amd64.zip . Python's original publisher signature is verified before packaging; the launcher and Setup.exe are unsigned.
- **Tcl/Tk 8.6.15**, copied only from the matching installed CPython 3.13.15 distribution: `licenses/Tcl-license.terms` and `licenses/Tk-license.terms`. Sources: https://github.com/tcltk/tcl/tree/core-8-6-15 and https://github.com/tcltk/tk/tree/core-8-6-15 .
- **zlib 1.3.1**, Jean-loup Gailly and Mark Adler — zlib license (`licenses/zlib-LICENSE.txt`); required by Tcl's `tcl86t.dll`. Source: https://github.com/madler/zlib/tree/v1.3.1 .
- Pillow, pystray and six notices above are copied to `licenses/`. Their unmodified Python source is shipped under `runtime/Lib/site-packages/`; pystray remains dynamically imported, editable and replaceable there without rebuilding the app. Modification/replacement for personal use and reverse engineering to debug those modifications are permitted under its LGPL terms. Upstream corresponding source is also linked above.
- Inno Setup 7.1.0 is a build-time tool, not a Python runtime dependency: https://jrsoftware.org/isinfo.php . Android platform-tools/ADB is **not bundled**; obtain it separately from Google and review its terms.
