# Publishing Lumobo

Everything the stores need is in this repo:

| What | Where |
|---|---|
| Title, short and full description, changelogs | [`fastlane/metadata/android/en-US/`](../../fastlane/metadata/android/en-US) |
| Icon (512×512), feature graphic (1024×500), 8 phone screenshots (1080×2160) | [`fastlane/metadata/android/en-US/images/`](../../fastlane/metadata/android/en-US/images) |
| Privacy policy | [`PRIVACY.md`](../../PRIVACY.md), URL: `https://github.com/amarsakore/lumobo/blob/main/PRIVACY.md` |
| F-Droid build recipe | [`app.lumobo.yml`](app.lumobo.yml) |

## Signing

Release builds are signed with the key in `keystore.properties` (never committed). Without that file the release build is **unsigned**, which is what F-Droid needs.

Create your own upload key once and keep it, and its passwords, safe and backed up:

```bash
keytool -genkeypair -v -keystore ~/lumobo-upload.jks -alias lumobo -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the project root:

```properties
storeFile=/Users/you/lumobo-upload.jks
storePassword=…
keyAlias=lumobo
keyPassword=…
```

Build:

```bash
./gradlew :app:bundleRelease     # Play: app/build/outputs/bundle/release/app-release.aab
./gradlew :app:assembleRelease   # GitHub: app/build/outputs/apk/release/app-release.apk
```

## Google Play

1. **Developer account:** sign up at play.google.com/console (one-time US$25 fee, identity verification).
2. **Create app:** name *Lumobo*, app, free.
3. **Play App Signing:** keep it on (Google holds the app signing key; your key above is the upload key). Upload `app-release.aab`.
4. **Closed test first.** New personal accounts must run a closed test with **at least 12 testers opted in for 14 days in a row** before applying for production.
5. **Store listing:** copy the text and images from `fastlane/metadata/android/en-US/`.
6. **App content:**
   - *Privacy policy:* the URL above.
   - *Ads:* no ads.
   - *Data safety:* the app collects and shares **no** data. It has no internet permission, and photos are processed on the device only.
   - *Content rating:* questionnaire with all "No" (no violence, user content, location sharing, purchases…). Expected rating: Everyone / 3+.
   - *Target audience:* 13+ (avoids the Families programme requirements).
   - *Photo and video permissions:* declare `READ_MEDIA_IMAGES` as core functionality. Suggested text: *"Lumobo is a gallery and photo editor. Its Library tab shows the user's photo albums so they can browse and pick photos to edit, and its Developed tab lists the photos it has edited. This needs persistent access to the user's photos; a one-time picker can't show the library."*
7. **Production:** after the 14-day test, apply for production access, then roll out.

Updates: raise `versionCode` / `versionName` in `app/build.gradle.kts`, add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, build the bundle and upload it.

## F-Droid

F-Droid builds Lumobo from source and signs it with its own key.

1. Make a GitLab account and fork https://gitlab.com/fdroid/fdroiddata.
2. Add [`app.lumobo.yml`](app.lumobo.yml) as `metadata/app.lumobo.yml` on a new branch.
3. Optionally check it with `fdroid lint app.lumobo` and `fdroid build app.lumobo` (fdroidserver).
4. Open a merge request. Reviewers may ask for small changes; new versions are then picked up automatically from git tags (`v2.0.1`, `v2.0.2`…).

F-Droid reads the listing (texts, icon, screenshots, changelogs) from `fastlane/` in this repo.

## Notes

- APKs signed with different keys can't update each other. Someone who installed from GitHub, Play or F-Droid keeps getting updates from that same source.
- Google's developer verification also applies to apps installed outside Play: on certified devices it starts in some countries in late 2026 and everywhere in 2027. Registering the app with Google (in the Play Console, or the Android Developer Console for apps outside Play) covers the GitHub APK too.
