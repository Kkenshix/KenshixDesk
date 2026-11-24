plugins {
    id("com.android.application") version "8.12.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
}

android {
    namespace = "com.kenshix.deck"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kenshix.deck"
        minSdk = 30
        targetSdk = 35
        versionCode = providers.environmentVariable("DECK_BUILD").orElse("1").get().toInt()
        versionName = "0.2.0"
        buildConfigField("String", "GIT_SHA", "\"" + providers.environmentVariable("DECK_SHA").orElse("local").get() + "\"")
    }

    buildFeatures { buildConfig = true }

    val signingFile = providers.environmentVariable("DECK_KEYSTORE").orNull
    if (signingFile != null) {
        signingConfigs.create("production") {
            storeFile = file(signingFile)
            storePassword = providers.environmentVariable("DECK_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("DECK_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("DECK_KEY_PASSWORD").get()
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("production")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
