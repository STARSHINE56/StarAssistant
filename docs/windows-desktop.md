# Windows Desktop — implementation and verification status

This branch is a development draft. It has **not** passed compilation, installation, Windows UI testing, or live authenticated drive acceptance. Do not describe it as a finished Windows release.

## Layout and source sharing

- `app`: existing Android application, same applicationId, signing, versions, UI and Room/SAF.
- `shared`: Kotlin/JVM library. Its Gradle source set compiles the authoritative existing Android `data/network` and `*ResolveRepository` files, plus DownloadFailurePolicy/HlsDownloader/HlsRequestPolicy. It does not copy APIs or package Android UI/storage. Android continues compiling these exact source files in app; app does not depend on the JVM jar, avoiding duplicate classes and Android `org.json` replacement.
- `desktopApp`: Compose Multiplatform 1.7.3/Kotlin 2.1.0/JDK 17 desktop UI, desktop storage, DPAPI encryption, persistent tasks and Windows integration.
- Android changes: replace framework Base64 with Okio codec to retain API 23 compatibility; replace HLS Android logging with JVM logging and omit exception text containing URLs. All other Android implementation/configuration is unchanged. Regression results are still required.
- JVM `DesktopFingerprint.kt` implements the platform-specific counterpart of Android SharedPreferences fingerprint persistence.

## Implemented paths, not live-verified capabilities

| Platform | Share parse/list | Cloud files/download | Credential input | Principal limitations |
|---|---|---|---|---|
| Quark | Existing resolve repository | Existing API | Cookie / account JSON | Live login and `__puus` refresh require verification; downloads may transfer to temporary cloud folders |
| UC | Existing resolve repository | Existing API | Cookie / account JSON | Membership/CDN rules; shared HLS only supports unencrypted supported playlists |
| Xunlei | Existing resolve repository | Existing API | accessToken + optional refreshToken/deviceId/captchaToken JSON | Expired tokens need refreshToken; verification and device binding may require manually imported matching fields |
| Baidu | Existing resolve repository | Existing API | Cookie / account JSON | Download membership and platform restrictions remain |
| 123 | Existing resolve repository | Existing API | accessToken / account JSON | Share listing may be anonymous; downloading and saving require authentication |
| Mobile | Existing resolve repository | Existing API | Full Cookie including account / Authorization | Missing account fields cannot resolve or transfer |

Desktop uses system browser + credential import. It has no embedded desktop browser or automatic browser Cookie extraction. JSON accepts Android account field names `cookie`, `accessToken`, `refreshToken`, `deviceId`, `captchaToken`; encrypted desktop backup format is not the Android AES backup format. Importing encrypted Android exports is not implemented.

## Download behavior

Actual OkHttp bytes, `.part` files, ETag/Last-Modified + Range/If-Range resumption, progress/speed/ETA, bounded retries and source-link reacquisition; file completion requires matching advertised/expected sizes. Nonresumable endpoints restart from zero. URLs, headers and task source metadata are encrypted with the rest of the state. Concurrent tasks are bounded. Cancellation retains partial data for resumption; deleting a task record leaves user files intact. Existing-file policy is rename by default, optional overwrite; in-flight task paths remain distinct.

HLS reuses the original bounded downloader. HLS resume restarts the playlist. Encrypted/BYTERANGE playlists remain unsupported. HLS throttling/speed metrics are not equivalent to ordinary HTTP downloads. Folder batch downloads currently flatten files into the chosen directory with collision renaming, rather than preserving hierarchy. Segment/multi-range parallelism is not implemented. Initial Quark temporary folders are cleaned after completion; refreshed-link temporary folders may remain and still require tracking.

## Native integration

JNA calls `DwmSetWindowAttribute` for attributes 20 (dark), 33 (round corners), and 38 (MAINWINDOW system backdrop), plus frame extension. Unsupported systems fall back to opaque background. A successful HRESULT is not visual proof of Mica: Skia/AWT compositor interaction and activation/resize behavior must be verified on Windows. Custom draggable title bar invokes native ShowWindow for min/max/restore; Native thick-frame resize/min/max styles are configured; edge dragging, Snap and accessibility still require Windows verification. Notification currently uses a system tray balloon, not modern Windows Toast.

System clipboard, desktop file opening, Explorer reveal, Windows native FolderBrowserDialog, system light/dark registry polling, automatic Compose DPI handling and compact navigation are present. Navigation highlight animation has an on/off toggle. The application reuses the Android icon as desktop PNG and multi-size Windows ICO; six platform-specific branded icons are still missing. Update action retrieves and displays the latest release version; a separate action opens the Release page.

## Build and release

Windows manual/PR/branch workflow: `Windows Desktop`, Windows x64 runner, JDK 17, Gradle cache, WiX 3, shared/desktop tests and `packageExe` + `packageMsi`.

```powershell
.\gradlew.bat -PdesktopOnly=true :shared:test :desktopApp:test :desktopApp:run
.\gradlew.bat -PdesktopOnly=true -PVERSION_NAME=1.0.1 :desktopApp:packageExe :desktopApp:packageMsi
```

Android check (existing SDK 36/build tools 35):

```sh
bash gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Existing Android Release workflow is untouched. After it publishes a signed APK, run Windows Desktop on the exact release commit with the same version; then `Attach Windows installers to Android release` validates commit, successful workflow, tag and APK before attaching EXE/MSI to that release. It does not overwrite existing assets or replace Android signing. Release secrets were not available for verification. Windows installers are not Authenticode signed.

Artifact names (planned, none generated in this environment):

- `XingChen-Assistant-vX.X.X-Windows-x64` containing `.exe` and `.msi`
- `Windows-test-reports`
- `Android-regression`

## Evidence and acceptance gaps

Local Gradle invocation failed before build configuration: downloading Gradle 9.0.0 from services.gradle.org returned `Network is unreachable`. No cached compiler/distribution was available. No tests were executed. The Windows Actions workflow has not run: GitHub connector writes (branch/tree creation) returned HTTP 403 Resource not accessible by integration; git push had no credential. The cloud browser subsequently created remote branch feature/windows-desktop and committed three workflow files as 1cae0f111d33be8f5c90750eb427315774c2956b. The source JSON upload reached the staging page, but the browser then timed out for all interactions and even reset. Read-only git ls-remote confirms that the branch remains at the workflow-only commit: no source import and no PR have been completed. PR creation through the connector also returned HTTP 403. A PR-only workflow-runs query returned no runs; this does not establish the state of push-triggered runs.

Written test cases cover six link formats, portable Base64, Windows filenames/traversal, encrypted credential persistence/backup passwords, real local HTTP bytes, persisted completion and rejecting mismatched sizes. Passing results cannot be claimed until execution.

Required further acceptance: Android three checks (and signed release if secrets available), shared/desktop tests and compilation, EXE/MSI packaging, install/start/uninstall/upgrade, live six-platform credentials and share/cloud/download operations, resume/cancel/retry including expiration, Mica/Windows 10 fallback, 1280×720/1366×768/1920×1080/2560×1440 and 125%/150% DPI visual checks. No screenshot or machine-level Windows evidence exists yet.

Continuation checkpoint: source files remained intact. Direct create-file request for shared/build.gradle.kts returned HTTP 403. GitHub plugin is installed/enabled, but installation/account listing returned empty arrays. Browser documentation recovery also timed out. Remote branch was reconfirmed at 1cae0f111d33be8f5c90750eb427315774c2956b. Added desktop icon resources and central Kotlin JVM/Compose plugin declarations locally; ICO structure and git diff checks passed. Gradle download again failed before compilation. Existing remote workflows were not recreated.
