# Tandem with RoMM support

**AI disclosure.** The RoMM integration was built with AI (Codex Astra + Sol 6).

An independently maintained modification of [Tandem](https://github.com/ZahakJ/tandem), the
open-source Android game frontend created by [ZahakJ](https://github.com/ZahakJ) for dual-screen
handhelds such as the AYN Thor. The original Tandem app, design, and the work shown in the linked
upstream screenshots are credited to ZahakJ and the upstream contributors.

**RoMM integration by [cocoaprime](https://github.com/cocoaprime) and this fork's contributors,
first added on 16 November 2026.** The goal is to make games from your own
[RoMM](https://github.com/rommapp/romm) server available alongside your local library in Tandem.
This fork is distributed under the same **[GNU GPL v3 or later](LICENSE)** as upstream Tandem.
See the [copyright and modification notice](NOTICE.md).

**[RoMM setup and downloads](#romm-setup-and-downloads)** ·
**[Original Tandem website](https://zahakj.github.io/tandem/)**

**[Releases](https://github.com/cocoaprime/tandem-romm-source/releases)**

## What this fork adds

- Connect to a RoMM 5.4.1 server with the server address and a dedicated API token.
- Browse RoMM platforms and games with cover art and metadata. Search your local and remote games
  from a Home system, where downloadable titles are dimmed and local games appear first.
- Download individual games or supported multi-file folders to your configured platform folder.
  The download queue can run while you switch apps, and finished games can appear in your local library
  with their metadata and artwork.

## RoMM setup and downloads

1. Open **Tandem Settings → Library & art → RoMM**. You can also open
   **Quick settings → Tandem settings**.
2. Enter your server address without the trailing `/api` (`https://` is added automatically if omitted).
   Add a dedicated RoMM Client API Token with `platforms.read` and `roms.read` permissions.
3. Turn on **Enable RoMM**. Select **Test RoMM connection** to confirm the server responds.
4. Open **Browse RoMM library** or select a dimmed game in a Home system. Check **Download to**
   before downloading. If Tandem cannot find a platform folder, it prompts you to choose one.

RoMM is optional. Private mode blocks access to the server.

[See the original Tandem screenshots and layouts](https://zahakj.github.io/tandem/).

## Inherited Tandem features

This branch incorporates ZahakJ's Tandem through upstream revision
[`653229d`](https://github.com/ZahakJ/tandem/commit/653229da392c25536c97fd39112e571e03a69121),
including 0.8.0 and the subsequent app updates. Alongside RoMM, it includes the
redesigned settings and quick controls, updated Orbit, French/Japanese/Arabic/Chinese
translations and right-to-left support, calendar choices, Calm music and Thock
sounds, sharing and browsing community looks, DroidDeck and GameNative library
imports, and the upstream native-game and save-sync improvements. RoMM-specific
text currently falls back to English.

- **Two screens.** Library on top; art, details and play time on the bottom. **X** swaps the screens with a little animation, and everything works on a single screen too.
- **While you play,** the bottom screen can show the game's art and session time, or a companion with a clock and quick controls, or stay free for emulators that use it.
- **Resume where you left off.** If a game is still open in its emulator, Tandem brings it back instead of restarting it; a game switcher lists every running game. Needs [Shizuku](https://shizuku.rikka.app/); without it, games start fresh as usual.
- **Controller first.** A to play, B back, Y options on anything, an on-screen keyboard for search, a **Start** panel, **hold Start** for quick settings (brightness, volume, Wi-Fi, Battery saver), **R2** for a side panel with your notifications, **Select** for a random unplayed game. Bluetooth pads get a working Home button and per-controller Nintendo A/B layout, and with Shizuku or root a pad connected mid-game can take over the built-in controls in any emulator. Touch works too.
- **Make it yours.** Theme packs (Tandem's own and Cocoon's, with their music and sounds) on top of three built-in themes, seven home layouts (shelves, an icon grid with 1 to 5 rows, carousel, list, console, Orbit, Tandem's own, and Spotlight, one game's art across the screen), animated and picture wallpapers, 15 widgets, an original soundtrack (Meadow Tales) or your own music, and eight UI sound packs.
- **Playing.** Stream PC games from a home computer with Moonlight, set performance and fan modes per game on AYN handhelds, keep notifications quiet with Do Not Disturb while a game plays, read a game's manual on the bottom screen, or turn that screen off.
- **Artwork and details.** Optional scraping from LaunchBox and libretro thumbnails (no account), ScreenScraper, IGDB and SteamGridDB (your keys) and HowLongToBeat. A setup guide tests each source; scrapes run in the background with progress on the bottom screen.
- **Play tracking.** Play time, sessions and streaks in a stats screen, a status for every game (backlog, playing, finished...), Tandem's own trophies on every system, a journal for notes, CSV export, RetroAchievements progress and Steam achievements.
- **Notes while you play,** per game, as plain Markdown files: text, checklists, glossaries with drawn symbols, sketches, and screenshots taken from the bottom screen straight into a note.
- **Organized your way.** Folders (shown as a grid, a bookshelf, a cartridge case or a carousel), smart folders that fill themselves, collections, favourites and Up next; hide anything you'd rather not see, with an optional PIN that also keeps Tandem offline.
- **Discord status** (optional, in builds with Discord's Social SDK): shows the game you're playing with its cover; off until you connect.
- **Home-screen mode,** with an apps drawer, a dock, and a screensaver (also as Android's own).
- **Your files as they are.** Reads `ROMs/<system>/`, turns subfolders into folders, uses and saves art in `ROMs/downloaded_media/` (the ES-DE layout), and imports `gamelist.xml` and other launchers' backups.
- **Open data.** Over 100 systems and their emulators live in an editable [`platforms.json`](app/src/main/assets/platforms.json); your library and settings are plain JSON with backup and restore.

## Supported systems

Tandem detects the emulators you have installed and offers every one that fits.
A few of the 104 systems it knows:

| System | Emulators |
| --- | --- |
| Nintendo 3DS · DS | Azahar · melonDS, DraStic, RetroArch |
| Switch · Wii U · Wii and GameCube | Eden · Cemu · Dolphin |
| Game Boy, Color, Advance · NES · SNES · N64 | RetroArch cores (mGBA, Gambatte, Mesen, Snes9x, Mupen64Plus…) |
| PlayStation · PS2 · PSP · Vita | DuckStation · NetherSX2, ARMSX2 · PPSSPP · Vita3K |
| Dreamcast · Saturn · Genesis and friends | Flycast, Redream · Yaba Sanshiro · RetroArch |
| Arcade · Windows · DOS · ScummVM | FinalBurn Neo, MAME · GameNative, Winlator · DOSBox Pure · ScummVM |

Any other emulator can be added by editing `platforms.json`, without rebuilding
the app: see [Systems and emulators](https://zahakj.github.io/tandem/docs/platforms/).

## Install

For RoMM support, build from this repository or use an APK published under
[this repository's Releases](https://github.com/cocoaprime/tandem-romm-source/releases).
The debug build installs as **Tandem RoMM Dev**. The release variant shares the upstream
application ID, and Android requires a matching signing key to update an existing installation.

For official Tandem downloads and updates, use
[ZahakJ's Releases](https://github.com/ZahakJ/tandem/releases/latest).

Android 11 or newer is required. Games, BIOS files and emulators come from your own setup.

## Quick start

1. Have your games in any folder on the device or an SD card: a folder per system (`n3ds`, `GBA`, `PlayStation 2`…), one system's folder, or everything together. `ROMs/` in internal storage is the default.
2. Install and open Tandem, then allow **All files access** so it can read your games.
3. Pick your games folder when asked, so Tandem can share games with emulators that need it.
4. Optional: with Shizuku running, allow Tandem in it to enable resume. For artwork, **Settings › Library & art › Scrape library**.

The [getting started guide](https://zahakj.github.io/tandem/docs/getting-started/) has the details, and
[Setting up on the AYN Thor](https://zahakj.github.io/tandem/docs/ayn-thor/) covers the Thor.

## Documentation

The [RoMM setup and downloads](#romm-setup-and-downloads) section covers this fork.
The [upstream documentation](https://zahakj.github.io/tandem/docs/) covers Tandem's original features,
including [AYN Thor setup](https://zahakj.github.io/tandem/docs/ayn-thor/), [controller controls](https://zahakj.github.io/tandem/docs/controls/)
and [the library](https://zahakj.github.io/tandem/docs/library/).

## Build an APK

Use JDK 17 and the Android SDK with platform 34 and build-tools 34.0.0. Set `ANDROID_HOME`
to your installed SDK or configure the SDK in Android Studio. The wrapper uses Gradle 8.9.

~~~sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest
~~~

On Windows, use `.\gradlew.bat` in place of `./gradlew`. The APKs are written to
`app/build/outputs/apk/`. Without a release signing configuration, the local release build uses
the debug key. Use the same release key as your installed APK to build an in-place update.

The Android NDK is optional and enables native controller passthrough. To build with Discord status,
supply the Discord Social SDK AAR under `third_party/discord/`, configure `discord.appId`
in local `local.properties`, and install the NDK and CMake.

The GitHub updater is disabled by default. Enable it with `-PselfUpdate=true` when building for
a public GitHub release feed.

## Contributing

Bug reports, emulator configs, docs and code are all welcome; see
[CONTRIBUTING.md](CONTRIBUTING.md). Please report security problems privately, as
described in [SECURITY.md](SECURITY.md).

## License

Tandem and the RoMM modifications are licensed under **GPL-3.0-or-later** and
distributed without warranty as set out in the [GNU GPL](LICENSE).
The [copyright and modification notice](NOTICE.md) credits the original authors,
describes the fork's changes and records how to find source for distributed APKs.
The [Nunito font](app/src/main/assets/licenses/OFL-Nunito.txt) retains its SIL Open Font License.
Game titles and artwork in the screenshots belong to their respective owners.
