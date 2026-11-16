// AGP 9.3.0 (published 14 July 2026) with its built-in Kotlin; the Kotlin Gradle plugin is declared
// without being applied so the build classpath carries 2.4.0 (published 3 June 2026) instead of the
// 2.2.10 AGP depends on. Both are over 90 days old on 1 November 2026 (docs/engineering-standards.md).
plugins {
    id("com.android.library") version "9.3.0" apply false
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}
