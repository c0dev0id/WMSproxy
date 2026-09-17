# Keep line numbers for readable stack traces in release builds.
-keepattributes SourceFile,LineNumberTable

# Ktor and OkHttp ship consumer rules, but Ktor's CIO engine is resolved through a
# ServiceLoader, which R8 cannot see. Without this the release build starts and then
# fails at runtime with "no engine found" — a failure that never appears in a debug build.
-keep class io.ktor.server.cio.** { *; }
-keep class io.ktor.server.engine.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }

# Coroutine debug metadata is only used for stack traces.
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
