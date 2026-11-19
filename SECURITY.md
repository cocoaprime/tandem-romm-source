# Security policy

This policy covers [Tandem with RoMM support](README.md), the independently maintained
integration in this repository.

## Supported versions

Only the latest release gets fixes. Please check you are on it before reporting, and say which version you tested.

## Reporting a vulnerability

If private vulnerability reporting is enabled, use **Security ? Report a vulnerability** ([reporting form](https://github.com/cocoaprime/tandem-romm-source/security/advisories/new)). Otherwise, contact the maintainer privately before sharing details. Please wait for a fix before opening a public issue or pull request about the vulnerability.

A useful report says what you did, what happened, and how to reproduce it. For RoMM issues, redact tokens, private server addresses and game paths from logs before attaching them.

## What is in scope

Tandem is an Android app with broad file access, so these matter most:

- Anything that lets another app, a file in `ROMs/`, a `platforms.json`, a `gamelist.xml` or a theme make Tandem read or write outside what the user intended, or start something other than an emulator.
- Ways the permissions Tandem holds (all files access, the shared `ROMs` folder, Shizuku) could be used by someone other than the user.
- Anything that sends data the user didn't ask to send, such as a hidden game, or leaks a stored key or login.
- Issues in this fork's RoMM connection, credentials or download handling.
- Problems with how this fork's release APKs are built and signed.

## What is not

- A custom `platforms.json` can start any app the user names. That is the feature: the file is in the user's own storage and only they can edit it.
- Emulators themselves, Shizuku, and the Android system have their own maintainers; report problems there.
- Problems in the third-party services themselves (scraping sources, RetroAchievements); report those to them.
