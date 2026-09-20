<h1 align="center">
  <img src="src/main/resources/icon.png" alt="Komm icon" width="80"><br>
  komm-launcher
</h1>

<p align="center">
  <b>The auto-updating launcher for <a href="https://kommvoice.com">Komm</a>, a free, self-hosted voice, video &amp; text chat platform.</b><br>
  Windows installer · Linux AppImage · Zero-maintenance updates · <code>komm://</code> invite links
</p>

<p align="center">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white">
  <img alt="JavaFX 25" src="https://img.shields.io/badge/JavaFX-25-1B6AC6">
  <img alt="jpackage + jlink" src="https://img.shields.io/badge/jpackage-private%20runtime-555555">
  <img alt="Windows | Linux" src="https://img.shields.io/badge/Windows%20installer-Linux%20AppImage-4CAF50">
  <img alt="License: MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

---

## What is Komm?

Komm is a modern chat platform built around a simple idea: **your community's messages and voice traffic belong on hardware you control.** Every community runs on its own self-hosted server: crystal-clear WebRTC voice channels, HD screen sharing, rich messaging, soundboards, roles & permissions, moderation tools and global hotkeys, all without handing your conversations to anyone else. Free, no ads, no tracking, on Windows 10/11 and Linux (both X11 and Wayland, with native PipeWire support).

The platform has three pieces; you choose how many to run:

| Piece | Role | Who runs it |
|---|---|---|
| [komm](https://github.com/B077AS/komm) (+ **komm-launcher**, this repo) | Desktop client for Windows & Linux, kept up to date by the launcher | Everyone |
| [komm-server](https://github.com/B077AS/komm-server) | A community's own server: channels, messages, voice rooms, permissions. One JAR, embedded database | Community owners |
| [komm-hub](https://github.com/B077AS/komm-hub) | The network's directory: accounts, friends, DMs, and the CA that vouches for servers | Almost nobody (most people use [kommvoice.com](https://kommvoice.com)) |

This repo is the thing you actually download from [kommvoice.com/download](https://kommvoice.com/download): a small, Discord-style bootstrapper that installs the Komm client, keeps it up to date on every start, and then gets out of the way. Install once, never think about updates again.

> 📥 **Just want to use Komm?** Grab the Windows installer or Linux AppImage from [kommvoice.com/download](https://kommvoice.com/download), both are built by this repo. You never need to build it yourself.

## What the launcher does

- **Checks for updates on every start**: asks GitHub for the client repo's latest release and swaps in the new JAR automatically, with a live progress bar
- **Works offline**: a bundled client seed makes the very first start work without internet; later, if GitHub is unreachable, the installed client launches as-is
- **Never breaks your install**: downloads are verified before they replace anything, and the swap is atomic (see [The update rules](#the-update-rules))
- **Handles invite links**: registers the `komm://` URI scheme so browser links like `komm://invite/CODE` open the launcher, which forwards them to the client
- **Ships its own Java**: installer and AppImage carry a private jlink runtime shared by launcher and client, so users never install Java
- **Looks like Komm**: a frameless, transparent, draggable window styled with the client's own AtlantaFX color tokens and icon; on Windows the client process even shows as **"Komm"** in Task Manager, not "Java Platform SE binary"

## How an update works

```
┌──────────────┐  1. GET api.github.com/repos/…/releases/latest  ┌──────────────┐
│   Launcher   │ ───────────────────────────────────────────────►│    GitHub    │  ← hosts the client's
│  (this repo) │ ◄───────────────────────────────────────────────│              │    releases + assets
└──────┬───────┘   { tag_name, assets: [{ name, url, digest }] } └──────────────┘
       │
       │  2. stale/missing? stream the asset's browser_download_url,
       │     verify against GitHub's own digest, atomically swap komm.jar
       ▼
┌──────────────┐
│ komm client  │  3. launched on the bundled runtime,
│  (komm.jar)  │     deep-link args forwarded
└──────────────┘
```

1. **Check**: `GET api.github.com/repos/B077AS/komm/releases/latest` returns the release's tag and its assets, each with a `browser_download_url` and a `digest` GitHub computes itself. No hub, sync service, or manual jar placement involved: GitHub *is* the source of truth.
2. **Compare**: the tag (minus a leading `v`) against the `client.version` embedded in the installed JAR's `app.properties`. The JAR is **self-describing**: launcher and client build read the *same* entry, so there is no separate version file to drift out of sync.
3. **Download**: if the installed JAR is missing or stale, the new one streams to `komm.jar.download` with determinate progress, is verified (it must be able to state its own version, and its SHA-256 must match the `sha256:<hex>` digest GitHub reported for that asset; a truncated, corrupt or tampered download is discarded, never installed), then atomically replaces `komm.jar`.
4. **Launch**: the client starts on the bundled runtime with any `komm://…` deep-link arguments forwarded, and the launcher closes.

### The update rules

- **Every update is mandatory.** Once GitHub has announced a newer release, a failed download shows an error; the launcher never falls back to a known-stale client. Everyone on the network runs the current version.
- **Offline is fine.** Only when GitHub itself is unreachable (you're offline, GitHub is down, or the unauthenticated API rate limit was hit) does an already-installed client launch as-is.
- **A corrupt download never replaces a working install.** The downloaded JAR must prove it can state its own version, and match the expected checksum, before it's moved into place.

## How the launcher updates itself

Everything above updates the *client*. But the launcher binary itself (the installer/AppImage you actually have on disk) has no equivalent "check on every start," because by the time there'd be anything to check, the launcher has already handed off to the client and exited. So **the client checks on the launcher's behalf**, once per start, and swaps it in the background:

```
┌──────────────┐  -Dlauncher.version=X   ┌──────────────┐  GET api.github.com/repos/…/releases/latest  ┌──────────────┐
│   Launcher   │ ──────────────────────► │ komm client  │ ─────────────────────────────────────────────►│    GitHub    │
│  (this repo) │   forwarded at launch   │  (komm.jar)  │◄────────────────────────────────────────────  │              │
└──────────────┘                         └──────┬───────┘  { tag_name, assets: [{ name, url, digest }] } └──────────────┘
                                                 │
                                                 │  stale (or version missing entirely)?
                                                 │  download the per-OS asset, verify against GitHub's
                                                 │  digest, swap, in the background
                                                 ▼
                                   Windows: overwrite app/komm-launcher.jar
                                   Linux:   overwrite the .AppImage at $APPIMAGE
```

- **The launcher is self-describing**, the same way the client JAR is: `launcher.version` in `app.properties`, set from this repo's own GitHub release tag at build time (see [Under the hood](#under-the-hood): `LauncherConfig.getLauncherVersion()`). `UpdateManager` forwards it to the client as `-Dlauncher.version=...` when spawning it.
- **A missing version means "definitely outdated."** Launchers built before this existed simply don't pass the flag at all; the client treats that exactly like this repo's own `UpdateManager` treats a missing client version: assume stale, update.
- **Windows and Linux need genuinely different artifacts, not just different natives.** On Windows, the launcher's own code is a discrete jar (`app/komm-launcher.jar`) inside an otherwise-untouched install directory, so the client just overwrites that one file; safe even while the client is running, since the launcher process that loaded it has already exited (`closeLauncher()` calls `System.exit()` right after spawning the client). On Linux there's no equivalent: an AppImage is one opaque, read-only-mounted unit, so "update the launcher" means replacing the *entire* `.AppImage` the client is running from; also safe while mounted, thanks to ordinary POSIX unlink-while-open semantics: the running instance keeps working off the old file until it next exits, the *path* just points somewhere new from then on.
- **Old installs self-repair, once.** Installs built before the launcher jar's filename was pinned to a constant (`komm-launcher.jar`, see `<finalName>` in `pom.xml`) have jpackage's `Komm.cfg` pointing at the old versioned name (`komm-launcher-0.0.1.jar`, etc.). The first swap on such an install also repoints `Komm.cfg` and deletes the stale jar; after that it's permanently on the fixed-name scheme and every future update is a plain file swap.
- **No UI, no restart prompt.** The swap is entirely passive; the new version is picked up the next time the user launches through the (already-updated) launcher, whenever that is.
- **This is why the launcher has its own release pipeline**: `.github/workflows/release.yml` in *this* repo, separate from the client's, publishing `komm-launcher-windows.jar` and `komm-launcher-linux.AppImage` on every launcher release. It's deliberately decoupled from client release cadence, so a launcher-only fix reaches every installed user, even the very first launcher ever shipped, without waiting on the next client release.

## Invite links (`komm://`)

The hub's invite pages offer an "Open in Komm app" link pointing at `komm://invite/CODE`. Packaged builds register the `komm://` URI scheme on every start:

- **Windows**: per-user registry keys under `HKCU\Software\Classes\komm`, no admin rights needed; re-registered on each start so the registration survives moving the install
- **Linux**: the AppImage's `.desktop` file declares `MimeType=x-scheme-handler/komm`

Clicking an invite in the browser starts the launcher with the URL as an argument; the launcher runs its normal update flow and hands the URL to the client, which opens the invite. Dev runs (`mvn javafx:run`) skip registration; there is no stable executable to register.

## What's inside the installer / AppImage

Both packages are built from the same jpackage **app-image**, wrapped in a platform-native shell, but they seed the client jar differently, because only Windows has a separate install phase to do it in:

```
Komm-Setup-<version>.exe
│
├─ Komm.exe                    the launcher itself
├─ komm-launcher.jar           launcher fat JAR
└─ runtime/                    private jlink runtime (Java 25 + JavaFX)
   └─ bin/Komm.exe             rebranded javaw.exe, the client's process
                               (shows as "Komm" in Task Manager)

Komm-<version>-x86_64.AppImage
│
├─ Komm                        the launcher itself
├─ komm-launcher.jar           launcher fat JAR
├─ komm-client-seed.jar        the client version current at build time
└─ runtime/                    private jlink runtime (Java 25 + JavaFX)
```

- **Windows seeds the client jar at install time.** Inno Setup's `[Files]` section (`packaging/windows/komm.iss`) copies the client fat jar straight into `%APPDATA%/Komm/bin/komm.jar`, unconditionally overwriting it on every (re)install; no seed jar rides inside the app-image, and `BundledClientSeeder` never runs on Windows (it finds nothing to seed and no-ops).
- **The AppImage still seeds on first run**, since an AppImage is one opaque, read-only-mounted unit with no install phase to hook into: `komm-client-seed.jar` ships next to the launcher jar, and `BundledClientSeeder` copies it into the app-data directory the first time there's no `komm.jar` there yet.
- **Either way** the normal GitHub-update flow owns the jar from the first launch on. The installer/AppImage is named after the client version it seeds (it's published on the client's release page), but any old installer stays valid forever, because the launcher updates the client on first contact anyway.
- **The private runtime is shared** by launcher and client: the launcher starts the client with its own `java.home`'s `javaw`/`java`, so users never install or update Java. (Both jpackage profiles override `--jlink-options` to keep native launchers like `bin/java` in the runtime, which jpackage strips by default.)
- **Task Manager branding (Windows)**: the build rewrites a copy of the runtime's `javaw.exe` into `runtime/bin/Komm.exe` with [rcedit](https://github.com/electron/rcedit) (icon + version resources), and the launcher prefers that copy when launching the client. Result: the client appears as **Komm** in Task Manager instead of "Java Platform SE binary".
- **AppImage mount guard (Linux)**: an AppImage's FUSE mount only lives as long as the process that started it, but the client runs off the mounted runtime. When the launcher detects it's running from an AppImage, a non-daemon `appimage-mount-keeper` thread keeps the (window-less) launcher process alive until the client exits, so the runtime never vanishes underneath it.

### First run

The launcher creates the app-data directory shared with the client: `%APPDATA%\Komm` on Windows, `~/.config/Komm` on Linux:

| Directory | Contents |
|---|---|
| `bin/` | `komm.jar`, the installed client, owned by the update flow |
| `logs/` | Launcher logs |

## Building from source (developers)

Requirements: **Java 25** and Maven.

```bash
# Dev run: checks GitHub, downloads/updates the client jar, launches it
mvn javafx:run

# Scripted visual demo: walks every phase with fake progress, no network or jar needed
mvn javafx:run -Dlauncher.demo=true

# Point at a fork's releases instead of B077AS/komm without rebuilding
mvn javafx:run -Dgithub.client.owner=you -Dgithub.client.repo=komm

# Fat jar with Windows + Linux JavaFX natives bundled
mvn clean package -Ppackage
```

The launcher always checks GitHub directly (`api.github.com/repos/B077AS/komm/releases/latest`); there's no hub URL to configure or bake in. To point a dev build at your own fork instead, pass `-Dgithub.client.owner=`/`-Dgithub.client.repo=` (see [Under the hood](#under-the-hood)).

### Windows installer

```bash
mvn clean package -Pinstaller
# → target/installer/Komm-Setup-<client.version>.exe
```

Needs the [komm](https://github.com/B077AS/komm) client fat JAR built next door (`mvn clean package -Ppackage` in `../komm`) and **Inno Setup 6** (`winget install JRSoftware.InnoSetup`; override the compiler location with `-Discc.path=...`). The build runs three steps: jpackage assembles the app-image with the private runtime, a PowerShell step rebrands the client's `javaw.exe` copy with rcedit (auto-downloaded on first use; override with `-Drcedit.path=...`), and Inno Setup wraps it all in a branded wizard: welcome art, install directory, desktop icon task, launch-on-finish.

### Linux AppImage

```bash
mvn clean package -Pappimage
# → target/appimage/Komm-<client.version>-x86_64.AppImage
```

Same client-JAR prerequisite, plus [appimagetool](https://github.com/AppImage/appimagetool) (default `~/.local/bin/appimagetool`; override with `-Dappimagetool.path=...`). `packaging/linux/make-appimage.sh` turns the jpackage app-image into an AppDir (AppRun, `.desktop` with the `komm://` scheme handler, icon) and runs appimagetool over it.

Both profiles accept the same overrides for which client to seed:

```bash
mvn clean package -Pinstaller -Dclient.version=0.0.2 -Dclient.jar=C:\path\komm.jar
```

### Self-update artifacts

Two more profiles exist purely for [the launcher's own self-update pipeline](#how-the-launcher-updates-itself): they carry only the platform-specific JavaFX natives, none of the installer/AppImage packaging steps, and just produce the plain fat jar:

```bash
mvn clean package -Pwin-natives    # target/komm-launcher.jar, Windows natives
mvn clean package -Plinux-natives  # target/komm-launcher.jar, Linux natives
```

`.github/workflows/release.yml` in this repo uses these to build `komm-launcher-windows.jar` directly, and (via the existing `appimage` profile, seeded with whatever the latest published [komm](https://github.com/B077AS/komm) client jar happens to be) `komm-launcher-linux.AppImage`.

## Under the hood

| Class | Responsibility |
|---|---|
| `Launcher` | Entry point: resolves app-data dirs, wires logging, hands off to the UI |
| `LauncherApp` | The frameless JavaFX window: phases, progress bar, animations; closes on ✕ or Esc |
| `LauncherConfig` | Reads `app.properties` (`launcher.version`) |
| `update/UpdateManager` | The whole check → download → verify → install → launch sequence on a worker thread; talks straight to `api.github.com`, repo overridable via `-Dgithub.client.owner=`/`-Dgithub.client.repo=` |
| `update/BundledClientSeeder` | Linux/AppImage only: first-run copy of the bundled client seed into the app-data dir (Windows seeds via Inno Setup instead, see [What's inside](#whats-inside-the-installer--appimage)) |
| `update/AppsFeaturesVersionUpdater` | Windows only: nudges the Inno Setup uninstall entry's `DisplayVersion` after every jar swap, so "Apps & Features" doesn't show the version from whenever the .exe was last run |
| `update/Phase` | The six status phrases (`CHECKING`, `DOWNLOADING`, `INSTALLING`, `STARTING`, `UP_TO_DATE`, `DONE`) |
| `ProtocolRegistrar` | `komm://` scheme registration (Windows registry; packaged builds only) |

`icon.png` is copied from the client, and `launcher.css` re-declares the client's AtlantaFX `-color-*` tokens; the launcher is deliberately a visual extension of the client, not a separate-looking app.

## Tech stack

| Layer | Technology |
|---|---|
| Language / runtime | Java 25 |
| UI | JavaFX 25, [AtlantaFX](https://github.com/mkpaz/atlantafx) design tokens, Ikonli (Feather icons) |
| Networking | Java `HttpClient` (version check + JAR streaming) |
| System integration | JNA / JNA Platform (OS detection, Windows specifics) |
| Packaging | jpackage app-image + private jlink runtime, Inno Setup 6 (Windows wizard), appimagetool (Linux), rcedit (exe rebranding) |
| Build | Maven, using `javafx-maven-plugin` for dev, `maven-shade-plugin` for the fat JAR, profiles for each package format |
| Misc | Gson, Lombok, Logback |

## Related repositories

| Repo | What it is |
|---|---|
| [komm](https://github.com/B077AS/komm) | Desktop client (JavaFX, Windows & Linux) |
| komm-launcher | This repo: auto-updating launcher, Windows installer & Linux AppImage |
| [komm-server](https://github.com/B077AS/komm-server) | Self-hosted community server (single JAR, embedded database) |
| [komm-hub](https://github.com/B077AS/komm-hub) | Accounts, friends & DMs, server directory, CA, and the kommvoice.com website |

## FAQ

**Why a launcher instead of a normal installer?** Komm's client and servers evolve together; the launcher guarantees everyone runs the current client without anyone ever clicking "download update". Install once; every start after that is automatically up to date.

**Does the launcher phone home anywhere else?** No. It makes exactly two requests, both to `api.github.com`: one for the latest release, one for the JAR asset (and the second only when an update is needed).

**Do I need Java installed?** No. The installer and AppImage bundle a private jlink runtime that both the launcher and the client run on.

**What happens if an update download fails mid-way?** Nothing bad: the download goes to a temporary file and is verified before it replaces anything. Your working install is only ever replaced by a JAR that proved it's intact. If GitHub has announced a new release, though, the update is mandatory: the launcher shows an error rather than starting an outdated client.

**Can I point the launcher at my own fork's releases?** Yes: pass `-Dgithub.client.owner=you -Dgithub.client.repo=komm` in dev. There's no hub to configure anymore; the launcher talks to GitHub directly.

**How does the launcher itself get updated?** See [How the launcher updates itself](#how-the-launcher-updates-itself); in short, the *client* checks on the launcher's behalf once per start and swaps it in the background, since the launcher process itself is already gone by the time the client is running.

## License

This project is licensed under the [MIT License](LICENSE).
