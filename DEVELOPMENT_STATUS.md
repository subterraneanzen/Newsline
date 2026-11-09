# Development status

_Last updated: 2026-11-09_

## Build status
- **GitHub Actions: passing.** Every push to `main` runs:
  - the `:core` unit tests (20 passing),
  - a debug APK build, published to the `latest-debug` release,
  - an unsigned release build with R8.
- **Not yet verified on a physical device.** Everything above was checked by compiling and by unit tests of the core logic. Collection, background runs, notifications and backup/restore still need hands-on testing on a phone.
- **Debug APK size:** about 58 MB, mostly the Compose icon library and debug metadata. A release build with R8 is much smaller.

## Implemented
- **Foundation:** Kotlin, Compose, Material 3, Room with FTS4, WorkManager, manual DI and a five-tab navigation bar (Home, Explore, Timeline, Telegram, Library).
- **Sources:**
  - RSS 2.0, RSS 1.0/RDF, Atom, XML and news sitemaps, plus page scanning for sites without a feed (marked partial).
  - Add a source by URL, with validation and feed auto-discovery.
  - Duplicate detection, and a warning when you already have a source from the same site.
  - Built-in directory of about 120 publishers.
  - OPML import and export.
  - Per-source enable, notify, interval, status, last success, errors and article list.
  - Remove a source, with a warning and a "just disable" alternative.
- **Collection:**
  - Runs on app launch, on manual refresh, and periodically through WorkManager (network-aware, with backoff).
  - Uses conditional GET, a per-run time budget, 3 sources in parallel and a cap of 30 new articles per source per run.
  - Keeps a run log with counts.
- **Extraction:**
  - Readability-style body extraction plus JSON-LD, OpenGraph and meta-tag metadata.
  - Detects opinion, editorial, analysis and review articles only from reliable metadata.
  - Honours paywall markup (`isAccessibleForFree`) and removes paywalled parts instead of bypassing them.
  - Falls back to feed content and explains why. Never invents text.
- **Versioning:**
  - Saves a first snapshot of every article.
  - Re-checks articles on a tapering schedule (1h → 7d, stopping after a configurable number of days).
  - Compares normalized content hashes, so formatting-only changes are ignored.
  - Uses a paragraph-level LCS diff with word-level detail for modified paragraphs, and reports headline, author and modified-date changes.
  - Marks an article as removed after two "not found" answers in a row.
  - A new paywall or a failed extraction is never recorded as removals.
- **History UI:** version list, compare any two versions (A/B), colour-coded added, removed, modified and unchanged text, and read any version in full. Publication, publisher-modified, discovery and capture dates are labelled separately.
- **Clustering:**
  - Matches stories on title, lead text and entity similarity, with a time window and a geography conflict penalty.
  - Requires shared specific names, so similar keywords alone don't merge stories.
  - Counts publishers by domain, so CBC's feeds count as one publisher.
  - Users can remove an article from a group.
- **Coverage display:** 2+ publishers shows an indicator, 3+ offers Compare Coverage, and 5+ is shown prominently on Home and can trigger a notification.
- **Timelines:** story timeline (reports plus captured updates) and topic or keyword timelines with Today / 7 days / 30 days / year / all ranges, grouped by day.
- **Geography:**
  - Built-in place list: all countries, Canadian provinces and territories, US states, some Australian and UK regions, and about 120 cities (strong Saskatchewan coverage).
  - Space detection.
  - Ambiguous names (Georgia, Jordan, Victoria…) need context before they count.
  - A publisher's location is only used, marked "from source", when an article names no place at all.
  - Users can correct tags manually, and their corrections are kept when the article is re-checked.
- **Reader:** clean typography, adjustable size and spacing, light, dark or system theme, optional images, previous/next navigation, and notices for extraction problems.
- **Search:** FTS4 over headline, body, publisher, author, places and topics, with filters for date range, publisher, place, topic and type. Searches the whole archive.
- **Library:** bookmarks, read later, collections, reading history, and followed topics, keywords, places and publishers with per-item notify switches.
- **Notifications:**
  - Local only, after a collection run.
  - De-duplicated per story, rate-limited, and silent during quiet hours.
  - Tapping one opens the archived article.
- **Telegram:** public web previews (`t.me/s/…`), a suggested channels list, add by @name or link, categories, follow/unfollow, feed, search, and original post links. Unavailable channels are labelled.
- **Storage:**
  - Shows database and archive size, and clears the image cache.
  - JSON export of the full archive, text export (one article, bookmarks, or everything).
  - Database backup and restore, with validation.
  - Duplicate clean-up with a preview.
  - Optional retention, off by default. It needs confirmation and always keeps bookmarked, saved and collected articles.

## Known limitations
- **Android scheduling:** background runs happen when Android allows. The minimum interval is 15 minutes and may be much longer in Doze or battery saver.
- **JavaScript-rendered sites:** pages that load their text with JavaScript can't be extracted. The feed summary is stored and the article is labelled.
- **Directory feeds:** feed URLs in the directory aren't guaranteed to stay valid. Each one is validated and its errors appear in Sources.
- **Telegram:** only public channels with web previews work. TDLib or MTProto integration, which would need the user's own API ID and phone login, is not implemented.
- **Classification:** topic and place tagging uses keywords and the built-in place list, so it can make mistakes. Manual correction is available.
- **Clustering scope:** stories are only matched within a 4-day window and the most recent signatures.
- **Database migrations:** schema version 1 only. Future schema changes must add Room migrations; destructive migration is deliberately disabled.

## Next steps
1. Get CI green and test on a device (first-run collection, background runs, a restore round-trip).
2. Collect real-world extraction failures from the directory sources and tune the extractor.
3. Add an instrumented test for Room queries and a migration test harness for schema v2.
4. Optional: Telegram TDLib mode, configured with the user's own credentials.
