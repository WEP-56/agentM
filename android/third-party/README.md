# Terminal components

The Java terminal emulator/view and `termux.c` are vendored from `termux/termux-app`, tag `v0.118.0`, downloaded from https://codeload.github.com/termux/termux-app/zip/refs/tags/v0.118.0 .

Only the terminal modules are used, under the upstream Apache-2.0 exception. See `Termux-upstream-exception.md` and `Termux-Apache-2.0.txt`; this does not apply to the entire Termux app.

agentM supplies its own Gradle/CMake integration and builds `libtermux.so` for arm64-v8a and x86_64 with 16 KB load alignment.

The JNI now incorporates DSHA's fork/exec identity handshake (`dsha-pty.c/.h`, source commit `70e37a7dbcae83b32fc92a8a37b33af88befc0e0`, MIT). The JNI callback class is changed to `dev.agentm.app.PtyIdentity`; the six Termux integration points follow DSHA's build script. See `DSHA-MIT.txt`. This is the PTY identity component only, not DSHA's entire native backup/runtime library.

`termux-sources.lock.json` records both the original and locally modified hashes where applicable.

agentM 0.9.0 adds display-only OSC 7 directory metadata and a user-requested visible-screen clear to `TerminalEmulator.java`. The clear does not send shell input or reset application input modes. These local modifications retain the terminal module's upstream license and are recorded in the source lock.

## Linux execution assets

`../runtime-assets.lock.json` pins official Termux packages for proot 5.1.107.96, libtalloc 2.5.0 and libandroid-shmem 0.7, including original package and prepared ELF hashes. The upstream package indexes were obtained over HTTPS from packages.termux.dev. The smaller `libtalloc.so.2` ELF name is replaced with `libtalloc.so` using NUL padding so Android packages and extracts the dependency under a `.so` filename. No unverified native binary is downloaded at application runtime.

Use `node tools/prepare-linux-runtime.mjs --prepare` from the workspace root to reconstruct these prepared files from pinned Debian packages; without `--prepare` it only verifies them. Gradle runs this verification before packaging.

Source/build recipe references are recorded under `runtime/`: proot source tag v5.1.107.96 (`https://github.com/termux/proot`), talloc 2.5.0 (`https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz`), and libandroid-shmem 0.7 (`https://github.com/termux/libandroid-shmem`). The recipe snapshot is not a claim of bit-for-bit source reproducibility of Termux's upstream binaries.

The packaged notices live in `app/src/main/assets/licenses`: proot GPL-2.0 text, talloc source notice and LGPL-3.0 text, libandroid-shmem BSD notice. Termux's talloc package metadata labels the package GPL-3.0; its source library LICENSE is LGPL-3.0. Keep the source notices and check actual redistributed components when publishing.

Ubuntu Base 24.04.5 amd64/arm64 is downloaded on demand from cdimage.ubuntu.com and checked against SHA-256 values pinned from the official SHA256SUMS file. Its `/usr/share/doc` notices remain in the rootfs. The image hash verifies bytes against the shipped catalog; it is not a separate GPG signature verification in the app.

## On-demand developer tools and Claude Code

`app/src/main/assets/agent-catalog.json` pins Node.js 24.21.0 official Linux tarballs and Claude Code 2.1.293 glibc Linux native npm packages for x64/arm64. `tools/prepare-agent-catalog.mjs` verifies Node's published SHA-256 and npm's published SHA-512 integrity, then records the SHA-256 and compressed length used by Android. Source metadata is preserved in `docs/research/agent-packages/metadata.json`. Changing versions is an explicit code/catalog change, not a runtime `latest` lookup.

The entire Node and Claude package contents, including LICENSE/README notices, remain in their installation slots. They are downloaded on demand and are not embedded in the APK. Claude Code is distributed under its own package license, not the licenses of the reference projects. Its audited wrapper postinstall copies/hardlinks `package/claude` into the command location; agentM uses that same platform binary directly without running npm lifecycle scripts. No protocol or conversation implementation is copied.

Git, Python and CA certificates use Ubuntu's signed package indexes and dpkg; installed versions are recorded in the actual probe output. These apt packages are not a reproducible dependency lock and may advance with the Ubuntu repository.

Codex 0.161.0 uses the official npm platform variants `@openai/codex@0.161.0-linux-x64` and `@openai/codex@0.161.0-linux-arm64`. Their complete native package layout is preserved, including code-mode/voice components, bwrap, ripgrep and bundled notices/licenses. Codex itself declares Apache-2.0; the additional bundled components retain their own notices. These packages are fetched on demand and are not embedded in the APK. The pinned wrapper was read and verified; agentM runs the platform binary in its own PTY and supplies the npm-managed marker. No claim is made that every optional component (such as voice) is functional on Android.

## Pi, OpenCode and DSH

Pi 1.1.0 uses its published CLI bundle and pinned quickjs-wasi 3.6.2 / photon-node 0.3.4 assets, with all package notices retained. OpenCode 1.18.35 uses official Linux x64-baseline / arm64 npm platform packages; no npm postinstall is run.

DSH 0.2.0-rc.2 reconstructs the Linux/glibc package graph from DSHA's locked npm dependency tree at commit `70e37a7dbcae83b32fc92a8a37b33af88befc0e0`. Each package keeps its license and notices. Fifteen runtime overlays derive from DSHA's MIT-licensed build recipe and its filesystem/session/combo-cache helpers. Their exact upstream inputs, before/after digests and output paths are recorded in `docs/research/agent-packages/dsh-adaptations.json`. The copied helper code and adaptations are covered by `DSHA-MIT.txt`; this does not relicense npm packages. The manifest and small overlays are embedded in the APK; the npm packages are downloaded and digest-verified on demand. DSH's node-pty helper chmod is the only explicitly reproduced lifecycle operation.

## Embedded WebView compatibility

`opencode-web-compat.js` bundles the Map.groupBy and Promise.withResolvers modules from core-js 3.41.0 (MIT), plus agentM's AbortSignal.any compatibility implementation. It is shared by OpenCode and DSH, injected at document start only for the active service origin. It does not replace conforming native APIs or introduce a management bridge. The core-js notice is included as `app/src/main/assets/licenses/core-js-MIT.txt`; pinned inputs and hashes are in `tools/opencode-web-compat/package-lock.json` and `docs/research/agent-packages/opencode-web-compat.json`.
