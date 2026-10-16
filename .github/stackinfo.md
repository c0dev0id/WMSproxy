# Stack

## Target

| | |
|---|---|
| Devices | Android phones running DMD2 |
| `minSdk` | 26 (Android 8.0) |
| `targetSdk` | 34 |
| `compileSdk` | 35 |
| JVM target | 17 |

## Build

| Tool | Version |
|---|---|
| Gradle wrapper | 8.9 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (with the Compose and serialization plugins) |
| CI | GitHub Actions, Temurin JDK 17, `gradle/actions/setup-gradle@v4` |

## Libraries

| Module | Library | Version |
|---|---|---|
| `:core` | kotlinx-serialization-json | 1.7.3 |
| `:core` | kotlinx-coroutines-core | 1.8.1 |
| `:app` | Compose BOM | 2024.10.01 |
| `:app` | Material 3, material-icons-extended | from the BOM |
| `:app` | navigation-compose | 2.8.9 |
| `:app` | activity-compose | 1.9.3 |
| `:app` | lifecycle (runtime, compose, viewmodel) | 2.8.6 |
| `:app` | core-ktx | 1.13.1 |
| `:app` | kotlinx-coroutines-android | 1.8.1 |
| `:app` | OkHttp | 4.12.0 |
| `:app` | osmdroid-android | 6.1.20 |
| tests | JUnit | 4.13.2 |

The HTTP server is the project's own (`core/http`). The update check uses
`HttpURLConnection`. The catalogue tool uses `java.net.http`.

## Scripts

`tools/check-library.py` and `tools/service-list.py` need Python 3 and only the
standard library. `check-library.py` calls `curl` for its requests.

## External services

| Service | Used for |
|---|---|
| `app.advhub.net` | DMD Hub sign-in and custom layers |
| `dns.codevoid.de` | The certificate of the HTTPS listener |
| `api.github.com` | The `dev` pre-release for the in-app update |
| `ows.terrestris.de` | The preview's OpenStreetMap base map |
