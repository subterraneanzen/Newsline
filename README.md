# News Archive (Newsline)

A personal Android news reader and archive. It collects articles from RSS/Atom feeds, sitemaps and publisher pages, and stores the full accessible text on your phone. It records every meaningful change publishers make to an article, groups coverage of the same story across outlets, and lets you browse everything by place, topic and time. There's no account, no cloud service and no tracking.

## Install the APK on your phone

1. On your phone, open **github.com/subterraneanzen/Newsline → Releases → "Latest debug build"** and tap **NewsArchive-debug.apk**.
2. Open the downloaded file. Android will ask you to allow installs from your browser ("Install unknown apps"). Allow it, then tap **Install**.
3. Open **News Archive**. The first launch enables a small starter set of sources and starts collecting. Add more under **⋮ → Sources**.

Every push to `main` rebuilds the APK automatically (Actions tab → *Build APK*). Each build also keeps a copy of the APK as a downloadable artifact.

## Building yourself

You need JDK 17 and the Android SDK (Android Studio installs both).

```
./gradlew :core:test            # unit tests for parsing, extraction, diff, clustering, geo
./gradlew :app:assembleDebug    # → app/build/outputs/apk/debug/app-debug.apk
```

### Signed release APK

1. Create a keystore once and keep it safe. Losing it means future updates can't install over the app:
   `keytool -genkey -v -keystore newsarchive.jks -keyalg RSA -keysize 4096 -validity 10000 -alias newsarchive`
2. Create `keystore.properties` in the project root. It's git-ignored, so never commit it:
   ```
   storeFile=newsarchive.jks
   storePassword=...
   keyAlias=newsarchive
   keyPassword=...
   ```
3. `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

The debug and release builds have different application IDs (`ca.newsarchive.app.debug` vs `ca.newsarchive.app`), so they install side by side. Use **Storage & export → Back up / Restore** to move your archive from one to the other.

## Architecture

| Layer | Where | Notes |
|---|---|---|
| Core logic (pure Kotlin/JVM, unit-tested) | `core/` | Feed/Atom/RDF/sitemap/OPML parsing, article extraction, text normalization and hashing, version diff, story clustering, geographic and topic classification, Telegram preview parsing, HTTP client, source directory |
| Data storage | `app/.../data` | Room (SQLite) entities and DAOs, FTS4 full-text index, settings |
| News ingestion | `app/.../ingest/Collector.kt` | Per-source collection with conditional GET (ETag/Last-Modified) |
| Article extraction and versioning | `app/.../ingest/ArticleProcessor.kt` | First snapshot, then revisits and new versions |
| Story clustering | `app/.../ingest/StoryService.kt` | Groups articles by publisher domain |
| Background monitoring | `app/.../work/SyncWorker.kt`, `ingest/SyncEngine.kt` | WorkManager |
| Telegram | `ingest/Collector.kt` (`TelegramCollector`), `core/telegram` | Public web previews |
| UI | `app/.../ui` | Jetpack Compose + Material 3 |

The only third-party dependency outside AndroidX is **jsoup** (HTML/XML parsing).
