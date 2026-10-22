# Security Policy

WallpaperHub is currently a personal project undergoing preparation for a public source release. No production-ready security guarantee is implied by the presence of code, tests, or successful CI builds.

## Reporting a vulnerability

Please **do not publish credentials, private media, device identifiers, proof-of-concept exploit details or private logs in a public issue**.

If GitHub's private vulnerability reporting is enabled for this repository, use **Security → Report a vulnerability**. Otherwise contact the maintainer privately using an appropriate contact route on their GitHub profile. No private security-report inbox or response-time SLA has been established.

A useful report includes affected commit/version, Android version, minimal non-sensitive reproduction steps and expected vs. actual result.

## Known security boundaries

- Android permissions and exported components must be verified against the specific commit and APK under review.
- `WifiSyncService.kt` is **legacy/inactive** in the audited main-branch Manifest (baseline commit `d0ab5e9a382bf0f97a81262c187d4f04b9e68bfd`). It has not been security-approved for reactivation.
- Historical pairing and archive-import code requires separate review, including PIN randomness and logging, input-size limits, archive traversal and file extraction resource controls, before being enabled.
- Treat sample media, screenshots, diagnostic logs and historical Git commits as potentially containing private or third-party information. They must pass a separate release review.

## Supported versions

There is **no officially supported public release yet**. The repository's Gradle version number describes a development snapshot, not a security-support commitment.
