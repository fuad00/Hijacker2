# Hijacker2 — internal-card edition (OnePlus 7 Pro / qcacld-3.0)

**🇬🇧 English** · [🇷🇺 Русский](README.ru.md)

A fork of [yesimxev/Hijacker](https://github.com/yesimxev/Hijacker) (itself a fork of
[chrisk44/Hijacker](https://github.com/chrisk44/Hijacker)) — a graphical frontend for the
Aircrack-ng suite (airodump-ng, aireplay-ng, MDK3, reaver) on Android.

**What's different:** this build makes airodump/aireplay work on the **built-in Wi-Fi card
of the OnePlus 7 Pro** (Qualcomm **qcacld-3.0**, FullMAC) — *no external USB adapter
required.* Upstream Hijacker assumes an mac80211/`airmon-ng`-style adapter and just prints
**"Airodump is not running!"** on this hardware. Here it captures **19 APs + client stations
across channels 1/6/11** on the internal card.

> Requires a kernel that exposes qcacld monitor mode + frame injection. See
> [Requirements](#requirements).

---

## Requirements

- **OnePlus 7 Pro** (`guacamole`, SM8150) on **OxygenOS 11**, bootloader unlocked, **Magisk** root.
- A **NetHunter kernel with monitor mode + packet injection on the internal card**
  (qcacld-3.0 port). Grab it from the 4PDA thread (op7 branch):
  **<https://4pda.to/forum/index.php?showtopic=954422&view=findpost&p=144130227>**
  Sanity check: `/sys/module/wlan/parameters/con_mode` must exist and accept the value `4`
  (monitor).
- The bundled aircrack-ng/`iw`/busybox binaries ship inside the APK (extracted to
  `/data/data/com.hijacker/files/bin` on first run).

Other Qualcomm devices with the same qcacld monitor/injection port may work too — the app
only assumes the `con_mode` sysfs knob and an `iw` that speaks nl80211.

## How it works (the qcacld adaptation)

Four device-specific problems had to be solved. All fixes live in the app itself:

| Problem | Root cause on qcacld | Fix |
|---|---|---|
| `"Airodump is not running!"` | Tools were launched with `su -c <cmd>`, which **inherits the app's `CapEff=0`** → no `CAP_NET_RAW` (raw socket) / `CAP_NET_ADMIN` (interface) / `CAP_DAC_OVERRIDE` (own `0700` bin dir). airodump dies instantly. | `execRoot()` runs every tool through a **login `su` shell** (`Runtime.exec("su")` + command on stdin), which Magisk grants **full capabilities**. |
| Monitor mode never turned on | `enable_monMode` was empty; `airmon-ng`/`iw type monitor` are ignored by a FullMAC driver. | Default `enable_monMode` switches `con_mode`: `echo 4 \| tee /sys/module/wlan/parameters/con_mode` (mksh can't open sysfs via `>`; `tee` can), waits for the ~30-40 s firmware re-init (polls `/sys/class/net/wlan0/type` == `803`/radiotap), then brings `wlan0` up. Runs off the UI thread; idempotent. |
| `"not running"` ~2 s after start | The watchdog saw `running == true` but no airodump PID *during* the 35 s enable window and force-stopped it. | An `Airodump.starting` flag; the watchdog ignores that window. |
| Empty AP list | airodump-ng's own channel hopping (wireless-ext ioctl) is **silently ignored** by qcacld, so `--band bg` never leaves one channel. | A **channel hopper** drives the channel with the bundled `iw` (nl80211, which the driver honours), cycling while airodump is alive and self-terminating when it exits. Honours a locked channel for handshake capture. |

Monitor mode is enabled at app launch and whenever airodump starts (you'll see an
*"Enabling monitor mode (~35 s)"* snackbar the first time — the qcacld firmware re-init is
slow, this is normal). It's turned back off (`con_mode=0`) when you leave the app, restoring
normal Android Wi-Fi.

## Install

1. Download **`Hijacker2-<version>.apk`** from the
   [Releases](https://github.com/fuad00/Hijacker2/releases) page (or the
   `Hijacker2-release-apk` artifact from a green [Actions](https://github.com/fuad00/Hijacker2/actions) run).
2. Install it. Grant root when Magisk asks (Superuser access = *Apps and ADB*).
3. Open it, accept the disclaimer, wait for monitor mode, tap **▶ Start**.

Release APKs are signed with a stable key, so updates install over each other. If you had a
differently-signed build (e.g. upstream Hijacker) installed, uninstall it first.

## Build from source

Fully reproducible on CI — you don't need a local toolchain:

- Push to `master` (or run the **Android CI** workflow manually) →
  [`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml) builds on
  `ubuntu-latest` with JDK 17, installs `ndk;26.1.10909125` + `cmake;3.22.1`, runs
  `./gradlew assembleDebug assembleRelease`, and uploads both APKs as artifacts.
- Locally: JDK 17, Android SDK 34, NDK 26.1.10909125 → `./gradlew assembleRelease`.

Build stack: **Gradle 8.5, AGP 8.2.2, compileSdk 34, targetSdk 29, minSdk 24**, native CSV
parser via CMake/NDK. (AGP-8 note: `android.nonFinalResIds=false` keeps the original
`switch(R.id.*)` code compiling.)

## Credits

- **chrisk44** — the original Hijacker.
- **yesimxev** — the maintained Hijacker fork this is based on.
- **pr0misc** and **2loch-ness6** — Gradle/SDK modernization and GitHub Actions CI that this
  build's toolchain was adapted from.
- **Loukious** — the qcacld-3.0 monitor/injection kernel port that makes the internal card
  usable in the first place.
- The **Aircrack-ng** and **Kali NetHunter** teams.

## License

GPLv3 (inherited from Hijacker). See [COPYING](COPYING).

## ⚠️ Legal

For use **only** on networks you own or are explicitly authorized to test. Monitor mode and
packet injection are regulated in many jurisdictions. You are responsible for how you use
this. No warranty.
