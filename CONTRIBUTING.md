# Contributing

Thanks for wanting to help with this [RoMM integration for Tandem](README.md). This repository is independently maintained. Please use its issue tracker and pull requests for changes to the integration. Bug reports, emulator configs, documentation fixes, translations and code are all welcome, and you don't need to be an Android developer for most of them.

## Ways to help

- **Report a bug.** Use the [bug report form](https://github.com/cocoaprime/tandem-romm-source/issues/new?template=bug_report.yml). Your device, Android version, the emulator involved and the steps to reproduce matter more than anything else.
- **Add an emulator or system.** If you got one working in your own `platforms.json`, share the entry with a [new emulator request](https://github.com/cocoaprime/tandem-romm-source/issues/new?template=new_emulator.yml), or open a pull request that adds it to [`app/src/main/assets/platforms.json`](app/src/main/assets/platforms.json). [Systems and emulators](https://zahakj.github.io/tandem/docs/platforms/) explains the format.
- **Suggest a feature.** Check the [open issues](https://github.com/cocoaprime/tandem-romm-source/issues) first, then use the [feature request form](https://github.com/cocoaprime/tandem-romm-source/issues/new?template=feature_request.yml).
- **Improve the docs.** Clarify build steps in the [README](README.md) or improve the contribution guides and issue forms.
- **Write code.** If you pick an open issue, comment there so people can coordinate.

## Getting the code running

[Building from source](README.md#build-an-apk) covers the setup. In short, with JDK 17 and the Android SDK (platform 34, build-tools 34.0.0):

```sh
git clone https://github.com/cocoaprime/tandem-romm-source && cd tandem-romm-source
./gradlew installDebug
adb logcat -s Tandem
```

## How the code is written

- **Controller first.** Every screen must be fully usable with the D-pad and A/B. Build focusable things from `FocusCard`, and restore focus with `remembersFocus`.
- **Sized for the handheld.** The top screen is about 830×470 dp, the bottom about 470×540 dp. Text must be readable at arm's length.
- **Friendly and plain.** Pastel palette, rounded corners, spring animations, Pip for empty states and messages. Write UI text the way you would explain it to a friend.
- **Private by default.** No analytics or telemetry, ever. Network access only for features the user switches on, and hidden games are never sent anywhere.
- **Small, separate changes.** Prefer a new file for a new feature, and keep edits to shared files small. Settings for a feature go in the feature's own file as a `SettingSection`.
- **Comments explain why,** not what the next line does.

## Pull requests

1. Fork this repository and branch from `main`. Target this repository for RoMM changes.
2. Make one change per pull request. A refactor riding along with a feature makes both harder to review.
3. Build with `./gradlew assembleDebug` and try the change on a device or emulator, with a controller if it touches the UI.
4. If you changed something visible, add a screenshot or a short video to the pull request.
5. If you changed behaviour that the docs describe, update the relevant documentation in the same pull request.

## Licence

Upstream Tandem and the RoMM changes use the [GNU General Public License, version 3 or later](LICENSE). See [NOTICE.md](NOTICE.md) for credits and modification dates. By contributing, you agree that your contribution is released under the same licence. Only contribute code you wrote yourself or that is under a compatible licence, and say so in the pull request if any of it came from elsewhere.

## Conduct

Be kind, assume good intent, and keep discussion about the work. The [code of conduct](CODE_OF_CONDUCT.md) has the details.
