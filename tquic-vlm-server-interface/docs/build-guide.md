# tquic-vlm-server-interface — Build Guide

This is not a plain `cargo build`: the `tquic` crate vendors BoringSSL and builds it from source via
`cmake` (C + assembly), so a real C toolchain is required alongside Rust. This guide covers what's
needed, the paths that work, and the gotchas actually hit while standing this crate up.

## Toolchain requirements

| Requirement | Why |
|---|---|
| Rust **1.90.0**, exact | Pinned in `rust-toolchain.toml` to match `tquic`; a mismatched default toolchain (e.g. a newer one already on the machine) can fail with a confusing "can't find crate for `core`" rather than a clear version error — see `tquic-jni`'s own `rust-toolchain.toml` comment for the same trap in the Android build. `rustup` auto-selects 1.90.0 the moment you `cd` into the crate directory, once that toolchain is installed. |
| `build-essential` (gcc/g++/make) | Compiling BoringSSL's C sources and linking |
| `cmake` | BoringSSL's own build system |
| `perl` | Used by BoringSSL's build scripts |
| `pkg-config` | Standard dependency-discovery tool several crates in the dependency tree expect |

No Android NDK, no `cargo-ndk`, no cross-compilation toolchain is needed for a same-architecture
Linux build — that whole category of complexity (documented in
`phone/shared/koog/http-client/http-client-tquic/native/README.md` for the Android `.so`) doesn't
apply here. This binary only needs to run on the Ubuntu x86_64 box it's deployed to.

## Recommended path: build directly on a real x86_64 Linux machine

This is by far the fastest and most reliable option, and is what actually produced the deployed
binary. If you have SSH access to the target machine (or any x86_64 Linux box), skip straight to
this — no emulation, no cross-compilation, ~1–2 minutes for a warm release build.

```bash
# On the target machine (Ubuntu, confirmed on 22.04 and 26.04):
sudo apt update
sudo DEBIAN_FRONTEND=noninteractive apt install -y build-essential cmake perl pkg-config curl ca-certificates

curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain 1.90.0
source "$HOME/.cargo/env"

# Get the source there. If working from a Windows/WSL dev machine over SSH, stream a tarball
# that excludes target/ rather than scp -r'ing a possibly-already-built tree:
#   tar --exclude='target' -czf - -C <repo-root> tquic-vlm-server-interface | \
#     ssh user@host "tar -xzf - -C ~/"

cd ~/tquic-vlm-server-interface
cargo build --release
cargo test --release   # 15 tests, all pure/mocked -- no network or real VLM needed
```

Observed timing on a 4-core AWS `t3`-class instance: `apt install` ~1 min, `rustup` install ~1 min,
`cargo build --release` **1m18s** cold (compiling `tquic` + vendored BoringSSL + ~80 other
dependency crates), `cargo test --release` a few seconds more (test binaries reuse the already-built
dependency graph).

### Verify the artifacts

```bash
file target/release/tquic-vlm-server-interface target/release/tquic-vlm-test-client
# ELF 64-bit LSB pie executable, x86-64, ... — confirms the target architecture

./target/release/tquic-vlm-server-interface --help   # sanity-check the CLI parses
```

For a full end-to-end check without a phone or a real VLM, see `README.md`'s "Testing without a
phone" section — a throwaway Python `http.server` stub standing in for the VLM backend, plus
`tquic-vlm-test-client` driving a real QUIC/H3/TLS request against the real server binary.

## Alternative: cross-compiling from a non-x86_64 dev machine

If no x86_64 Linux machine is reachable (e.g. only an ARM64 Windows/Mac dev box is available),
build inside an emulated x86_64 environment instead. Two sub-options, both slower than the
same-architecture path above because BoringSSL's C/asm build runs under emulation:

**Docker + [`cross`](https://github.com/cross-rs/cross)** — if Docker is available, `cross build
--target x86_64-unknown-linux-gnu --release` handles the emulated C toolchain via a prebuilt
container. Not used for this crate (Docker wasn't available on the dev machine at the time), but
it's the more standard/robust tool for exactly this situation if Docker permissions aren't a
concern.

**`qemu-user-static` + `debootstrap` chroot, inside WSL2** — reuses the same emulation mechanism
`tquic-jni`'s Android build already documents for running the NDK's x86_64-only clang from an ARM64
WSL host:

```bash
# Inside WSL2 (any architecture):
sudo apt install -y qemu-user-static binfmt-support debootstrap

sudo debootstrap --arch=amd64 jammy /opt/x86_64-chroot http://archive.ubuntu.com/ubuntu
sudo cp /usr/bin/qemu-x86_64-static /opt/x86_64-chroot/usr/bin/
sudo mount --bind /dev  /opt/x86_64-chroot/dev
sudo mount --bind /proc /opt/x86_64-chroot/proc
sudo mount --bind /sys  /opt/x86_64-chroot/sys

sudo chroot /opt/x86_64-chroot /usr/bin/qemu-x86_64-static /bin/bash
# ---- now inside the emulated x86_64 chroot: same apt/rustup/cargo steps as above ----
```

**In practice this path was abandoned partway through** in favor of a real remote x86_64 machine
once one became available — the `apt install` step alone for the build toolchain was still running
after several minutes under emulation, versus ~1 minute natively. Treat this as a documented
fallback, not the first thing to reach for if any real x86_64 Linux host (even a temporary cloud
VM) is reachable instead.

## Gotchas actually hit while building this crate

- **Disk space on a small root volume.** Installing the Rust toolchain (~1.3G for `.rustup` alone)
  plus a release build's intermediate objects (~500M+ for `target/release`, across `tquic` + BoringSSL
  + ~80 dependency crates) added up to more than a 6.7G EBS root volume had free, hitting **100%
  full, 0 bytes available** on a real deployment. Recovery, in order (deletions first, since they
  need no free space; only copy things out *after* space exists):
  1. `sudo apt clean` — clears `/var/cache/apt/archives` (freed 462M in this case)
  2. `rm -rf ~/.cargo/registry/{cache,src}` — safe, cargo re-fetches on demand (freed 144M)
  3. *Now* copy the final binaries somewhere safe (`target/release/{binary names}` are only a few MB
     each)
  4. `cargo clean` — reclaims the rest of `target/release`'s intermediate build objects (freed 523M
     in this case)

  A currently-running process executing the binary survives step 4 fine — Linux keeps a deleted
  file's inode alive via the process's open file descriptor, it just no longer has a path on disk.
  If you'll rebuild again later, budget for `cargo build --release` needing ~500M of scratch space
  each time.

- **`rust-toolchain.toml` pin must match exactly.** If a different default toolchain is already
  active on the build machine, `rustup run 1.90.0 cargo ...` (explicit) is more reliable than relying
  on auto-detection in a one-off `bash -lc` invocation, especially from a non-interactive shell.

- **Windows can't rename/delete a directory that any process has as its current working directory**
  — including your own terminal. If a directory move/rename fails with "Access is denied" on
  Windows with no obvious lock, check whether a shell (or an editor's integrated terminal) is `cd`'d
  into it first.

- **Git Bash / MSYS mangles POSIX-looking absolute paths** passed as top-level arguments to
  non-MSYS-aware executables like `wsl.exe` (e.g. `wsl.exe -- chroot /opt/x86_64-chroot ...` becomes
  `chroot: cannot change root directory to 'C:/Program Files/Git/opt/x86_64-chroot'`). Wrapping the
  whole remote command in `bash -lc "..."` as a single quoted string avoids this, since MSYS doesn't
  rewrite paths embedded inside a string argument.

- **`cargo check --offline`** fails the first time dev-dependencies (e.g. `mockito`, pulled in only
  by `#[cfg(test)]` code) haven't been fetched yet — run once without `--offline` to populate the
  local cache, then `--offline` works for fast iteration afterward.

## Quick reference

```bash
# Fastest path end-to-end, from a fresh Ubuntu x86_64 machine with SSH access:
ssh user@host 'sudo apt update && sudo DEBIAN_FRONTEND=noninteractive apt install -y \
  build-essential cmake perl pkg-config curl ca-certificates'
ssh user@host 'curl --proto "=https" --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain 1.90.0'
tar --exclude='target' -czf - tquic-vlm-server-interface | ssh user@host 'tar -xzf - -C ~/'
ssh user@host 'source ~/.cargo/env && cd ~/tquic-vlm-server-interface && cargo build --release && cargo test --release'
```
