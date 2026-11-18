import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    `maven-publish`
    signing
}

group = "ai.matca"
version = "1.1.0"

android {
    namespace = "ai.matca.edge"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "VERSION", "\"${project.version}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Versions move under the 90-day rule (docs/engineering-standards.md), never because a newer one exists.
    lint {
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// The device tests read the JVM tests' fixtures, so the picture and model files stay in one place.
androidComponents {
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("src/test/resources/fixtures")
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:2.1.6")
    // A Watch takes and answers a Flow, so an app compiles against coroutines too.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    // Android has org.json at every API level; the JVM the tests run on does not.
    testImplementation("org.json:json:20251224")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}

// The folder scripts/publish-maven-central.sh zips for Maven Central, which wants every file signed and checksummed.
publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "edge"
            afterEvaluate { from(components["release"]) }
            pom {
                name = "Matca edge"
                description = "Runs a Matca device model on an Android phone and keeps it up to date from a signed release."
                url = "https://github.com/matca-ai/edge-android"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        name = "Matca"
                        organization = "Matca"
                        organizationUrl = "https://matca.ai"
                    }
                }
                scm {
                    url = "https://github.com/matca-ai/edge-android"
                    connection = "scm:git:https://github.com/matca-ai/edge-android.git"
                    developerConnection = "scm:git:https://github.com/matca-ai/edge-android.git"
                }
            }
        }
    }
    repositories {
        maven {
            name = "bundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }
}

// Unsigned when no key is set: a local build needs none, and the publish script refuses to upload without one. The
// release workflow holds a signing subkey only, exported without its primary's secret, so it names the subkey's id.
providers.environmentVariable("MAVEN_SIGNING_KEY").orNull?.let { key ->
    signing {
        val password = providers.environmentVariable("MAVEN_SIGNING_PASSWORD").getOrElse("")
        val subkey = providers.environmentVariable("MAVEN_SIGNING_KEY_ID").orNull?.takeIf { it.isNotBlank() }
        if (subkey != null) useInMemoryPgpKeys(subkey, key, password) else useInMemoryPgpKeys(key, password)
        sign(publishing.publications["release"])
    }
}
