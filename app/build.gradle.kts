plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.about.libraries)
}

android {
    namespace = "dev.ndcshelf.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ndcshelf.app"
        minSdk = 23
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.apache.commons.csv)
    implementation(libs.aboutlibraries.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

val verifyLicenseReport by tasks.registering {
    group = "verification"
    description = "Verifies that the release license report covers direct and transitive dependencies."
    dependsOn("prepareLibraryDefinitionsRelease")

    val report = layout.buildDirectory.file(
        "generated/aboutLibraries/release/res/raw/aboutlibraries.json",
    )
    inputs.file(report)
    doLast {
        val contents = report.get().asFile.readText()
        val libraryCount = "\"uniqueId\":".toRegex().findAll(contents).count()
        check(libraryCount >= 100) {
            "Expected a transitive release dependency report, but found only $libraryCount entries."
        }
        check("\"uniqueId\":\"org.apache.commons:commons-csv\"" in contents) {
            "A known direct dependency is missing from the release license report."
        }
        check("\"uniqueId\":\"com.squareup.okio:okio-jvm\"" in contents) {
            "A known transitive dependency is missing from the release license report."
        }
        check("\"licenses\":[]" !in contents) {
            "At least one release dependency has no declared license or terms."
        }
        mapOf(
            "Apache-2.0" to "Apache License\\nVersion 2.0",
            "BSD-3-Clause" to "Redistribution and use in source and binary forms",
            "MIT" to "MIT License",
        ).forEach { (spdxId, contentMarker) ->
            check("\"spdxId\":\"$spdxId\"" in contents) {
                "The bundled $spdxId license text is missing from the release report."
            }
            check(contentMarker in contents) {
                "The bundled $spdxId license content is empty in the release report."
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyLicenseReport)
}
