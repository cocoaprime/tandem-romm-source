# Tandem with RoMM support

An independent modification of [Tandem](https://github.com/ZahakJ/tandem), the Android game
frontend created by [ZahakJ](https://github.com/ZahakJ) and its contributors. The RoMM integration
adds library browsing, search, metadata and downloads from a user-configured RoMM 5.4.1 server.

## Build an APK

Use JDK 17 and the Android SDK with platform 34 and build-tools 34.0.0. Set `ANDROID_HOME`
to your installed SDK or configure the SDK in Android Studio. The wrapper uses Gradle 8.9.

~~~sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
~~~

On Windows, use `.\gradlew.bat` in place of `./gradlew`. APKs are written under
`app/build/outputs/apk/`. Debug builds install as **Tandem RoMM Dev**. Release builds use the
development key by default for local testing. Use your existing release keystore through
local `keystore.properties` or an external signing step to build an in-place update for an
APK signed with that release key.

The Android NDK is optional and enables the native controller passthrough helper.
For optional Discord status, supply the Discord Social SDK AAR under `third_party/discord/`,
configure `discord.appId` in local `local.properties`, and install the NDK and CMake.
The standard build runs without those files. Enable the GitHub updater with `-PselfUpdate=true`
only for builds distributed through a public release feed.

## Tests

~~~sh
./gradlew :app:testDebugUnitTest
~~~

## License

Distributed under the GNU General Public License v3 or later. See [LICENSE](LICENSE),
[NOTICE.md](NOTICE.md), and the bundled [Nunito font license](app/src/main/assets/licenses/OFL-Nunito.txt).
