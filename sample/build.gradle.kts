import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "ai.matca.edge.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.matca.edge.sample"
        minSdk = 24
        targetSdk = 36
        // The publishing job numbers each build it hands to phones (.forgejo/workflows/android.yaml).
        versionCode = System.getenv("MATCA_TEST_APP_BUILD")?.toInt() ?: 1
        versionName = "1.0"
    }

    // The release a phone installs from the edge is signed with Matca's own test-app key, which the publishing job
    // writes to a file from its secret and removes again; without it a release build is left unsigned.
    signingConfigs {
        create("matca") {
            storeFile = System.getenv("MATCA_TEST_APP_KEYSTORE")?.let(::file)
            storePassword = System.getenv("MATCA_TEST_APP_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MATCA_TEST_APP_KEY_ALIAS")
            keyPassword = System.getenv("MATCA_TEST_APP_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("matca").takeIf { it.storeFile != null }
        }
    }

    // A test app that is never published: no store listing, no translations, no versions of its own to chase.
    lint {
        disable += setOf(
            "AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi",
            "DataExtractionRules", "MissingApplicationIcon", "SetTextI18n",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":edge"))
}
