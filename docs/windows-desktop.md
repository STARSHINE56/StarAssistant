# Windows Desktop — implementation and verification status

This branch has passed shared/desktop unit tests, desktop compilation, and the three Android regression tasks in GitHub Actions. It remains a development draft pending installation, Windows UI and live authenticated drive acceptance. Do not describe those manual acceptance checks as completed.

## Layout and source sharing

- `app`: existing Android application, same applicationId, signing, versions, UI and Room/SAF.
- `shared`: Kotlin/JVM library. Its Gradle source set compiles the authoritative existing Android `data/network` and `*ResolveRepository` files, plus DownloadFailurePolicy/HlsDownloader/HlsRequestPolicy. It does not copy APIs or package Android UI/storage. Android continues compiling these exact source files in app; app does not depend on the JVM jar, avoiding duplicate classes and Android `org.json` replacement.
- `desktopApp`: Compose Multiplatform 1.7.3/Kotlin 2.1.0/JDK 17 desktop UI, desktop storage, DPAPI encryption, persistent tasks and Windows integration.
- Android changes: replace framework Base64 with Okio codec to retain API 23 compatibility; replace HLS Android logging with JVM logging and omit exception text containing URLs. All other Android implementation/configuration is unchanged. The three Android regression tasks passed in Actions.
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

Configured artifact names (check the latest successful Actions run for availability):

- `XingChen-Assistant-vX.X.X-Windows-x64` containing `.exe` and `.msi`
- `Windows-test-reports`
- `Android-regression`

## Evidence and acceptance gaps

Source recovery was completed on 2026-10-03 using the cloud browser and the existing branch-only importer. Commit `97e6127644e08289c1d59057fee53d51fefd1da4` has the exact source tree of retained local commit `52a3cea2720dbd27b89afe4a3cdb58e6c1f9ae08`, including normal Git files under shared/desktopApp and binary icons. The temporary import JSON was removed automatically. Existing Android release/signing files were untouched.

Evidence: [Windows Desktop run 37109857353](https://github.com/STARSHINE56/StarAssistant/actions/runs/37109857353) passed `:shared:test` (2 tests), `:desktopApp:test` (4 tests), `:desktopApp:compileKotlin`, and Android `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`. It failed only in installer packaging. CI fixes reuse preinstalled WiX 3 and enforce UTF-8 for JDK 17 jpackage arguments. Installer metadata uses ASCII description/menu group for WiX's English code page; the application UI remains Chinese. The latest branch run is the authority for installer build success and uploaded artifacts.

Tests execute real local HTTP downloads, encrypted credential persistence/backup password rejection, completed-task persistence, mismatched-size failure, Windows filename handling, six link formats and portable Base64. They do not validate live authenticated platform accounts, UI layout or installation. Local Gradle download remains blocked by the sandbox network; the checks above ran on GitHub runners.

Required further acceptance: signed Android release if secrets available, installer artifact verification, install/start/uninstall/upgrade, live six-platform credentials and share/cloud/download operations, resume/cancel/retry including expiration, Mica/Windows 10 fallback, 1280×720/1366×768/1920×1080/2560×1440 and 125%/150% DPI visual checks. No screenshot or machine-level Windows evidence exists yet.
