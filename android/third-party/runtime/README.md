# Redistributed Linux runtime sources

Every GitHub Release includes `linux-runtime-sources.tar.gz` alongside the APK.
The bundle contains the full upstream PRoot 5.1.107.96, talloc 2.5.0 and
libandroid-shmem 0.7 sources, their licenses, and a pinned complete Termux
package-building repository with the matching package recipes and patches.
Archive URLs and SHA-256 digests are in `sources.lock.json`; the workflow fails
if a source archive does not match its digest.

To create the same source bundle from the project root:

```sh
python3 tools/release/bundle-runtime-sources.py
```

To build the upstream components, unpack the Termux build repository and follow
its included `README.md` / `CONTRIBUTING.md` and build environment documentation.
Its `packages/proot`, `packages/libtalloc` and `packages/libandroid-shmem`
directories contain all package-specific recipes and patches. In that build
environment, the package entry point is `./build-package.sh -a aarch64 proot`
(or `-a x86_64`); the dependent libraries are built through those recipes.
Source URLs in the recipes can be supplied from the bundled upstream archives.

agentM redistributes the Termux binary packages pinned in
`android/runtime-assets.lock.json`. The agentM-specific preparation is fully
recorded in `tools/prepare-linux-runtime.mjs`: extract the named ELF members,
rename `libtalloc.so.2` strings with equal-length NUL padding, and package the
files under Android-compatible `.so` names. That script's `--prepare` mode
reconstructs the packaged files from the pinned Debian packages; its default
mode checks the resulting hashes. The bundle is source and build material,
not a claim that Termux's historical binaries are bit-for-bit reproducible.

PRoot is GPL-2.0; the talloc library's source license is LGPL-3.0 (Termux labels
its package GPL-3.0); libandroid-shmem uses BSD-3-Clause. The source archives
retain their complete original notices. These terms are not replaced by
agentM's MIT license. Modified libraries/runtime can be repackaged into an APK
using agentM's open build and a user-owned signing key; no vendor signing key
is required to build and install a separate copy.
