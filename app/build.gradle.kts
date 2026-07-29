plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.about.libraries)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.ndcshelf.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ndcshelf.app"
        minSdk = 23
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // 署名鍵はリポジトリへ置かず、CIのrelease environment secretsまたは
        // ローカル環境変数からだけ供給する。未設定ならreleaseは未署名で生成される。
        val keystorePath = System.getenv("NDC_SHELF_UPLOAD_KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NDC_SHELF_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NDC_SHELF_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("NDC_SHELF_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets {
        // Robolectric reads migration fixtures from the debug target assets; release stays clean.
        getByName("debug").assets.directories.add("$projectDir/schemas")
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        getByName("test").resources.directories.add("$rootDir/fixtures")
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
    implementation(libs.androidx.navigation.compose)
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
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.apache.commons.csv)
    implementation(libs.aboutlibraries.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.rule)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

val generatedLicenseReport =
    layout.buildDirectory.file(
        "generated/aboutLibraries/release/res/raw/aboutlibraries.json",
    )

val generateThirdPartyNotices by tasks.registering(Copy::class) {
    group = "documentation"
    description = "Generates the third-party notice artifact from release dependencies."
    dependsOn("prepareLibraryDefinitionsRelease")
    from(generatedLicenseReport)
    into(layout.buildDirectory.dir("reports/licenses"))
    rename { "THIRD-PARTY-NOTICES.json" }
}

val verifyLicenseReport by tasks.registering {
    group = "verification"
    description = "Verifies that the release license report covers direct and transitive dependencies."
    dependsOn(generateThirdPartyNotices)

    val report = layout.buildDirectory.file("reports/licenses/THIRD-PARTY-NOTICES.json")
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

tasks.cyclonedxDirectBom {
    includeConfigs = listOf("releaseRuntimeClasspath")
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    componentName = "NDC Shelf"
    componentVersion = android.defaultConfig.versionName ?: "unspecified"
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("reports/cyclonedx/ndc-shelf.cdx.json")
    xmlOutput.unsetConvention()
}

val verifyBackupPolicy by tasks.registering {
    group = "verification"
    description = "Verifies the fail-closed cloud backup and OS-specific D2D rules."

    val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
    val legacyRules = layout.projectDirectory.file("src/main/res/xml/backup_rules.xml")
    val api28Rules = layout.projectDirectory.file("src/main/res/xml-v28/backup_rules.xml")
    val modernRules = layout.projectDirectory.file("src/main/res/xml/data_extraction_rules.xml")
    inputs.files(manifest, legacyRules, api28Rules, modernRules)

    doLast {
        val factory =
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }

        fun parse(file: File) = factory.newDocumentBuilder().parse(file)

        fun childElements(
            parent: org.w3c.dom.Element,
            tag: String,
        ): List<org.w3c.dom.Element> =
            (0 until parent.childNodes.length)
                .map { parent.childNodes.item(it) }
                .filterIsInstance<org.w3c.dom.Element>()
                .filter { it.tagName == tag }

        fun assertRule(
            element: org.w3c.dom.Element,
            domain: String,
            flags: String = "",
        ) {
            check(element.getAttribute("domain") == domain && element.getAttribute("path") == ".")
            check(element.getAttribute("requireFlags") == flags)
        }

        val application =
            parse(manifest.asFile)
                .getElementsByTagName("application")
                .item(0) as org.w3c.dom.Element
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        check(application.getAttributeNS(androidNamespace, "allowBackup") == "true")
        check(application.getAttributeNS(androidNamespace, "fullBackupContent") == "@xml/backup_rules")
        check(
            application.getAttributeNS(androidNamespace, "dataExtractionRules") ==
                "@xml/data_extraction_rules",
        )

        val allDomains =
            setOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
        val legacyRoot = parse(legacyRules.asFile).documentElement
        check(childElements(legacyRoot, "include").isEmpty())
        val legacyExcludes = childElements(legacyRoot, "exclude")
        check(legacyExcludes.map { it.getAttribute("domain") }.toSet() == allDomains)
        legacyExcludes.forEach { check(it.getAttribute("path") == ".") }

        val api28Root = parse(api28Rules.asFile).documentElement
        check(childElements(api28Root, "exclude").isEmpty())
        val api28Includes = childElements(api28Root, "include")
        check(api28Includes.size == 1)
        assertRule(api28Includes.single(), "database", "deviceToDeviceTransfer")

        val modernRoot = parse(modernRules.asFile).documentElement
        val cloud = childElements(modernRoot, "cloud-backup").single()
        check(childElements(cloud, "include").isEmpty())
        val cloudExcludes = childElements(cloud, "exclude")
        check(cloudExcludes.map { it.getAttribute("domain") }.toSet() == allDomains)
        cloudExcludes.forEach { check(it.getAttribute("path") == ".") }

        val deviceTransfer = childElements(modernRoot, "device-transfer").single()
        check(childElements(deviceTransfer, "exclude").isEmpty())
        val transferIncludes = childElements(deviceTransfer, "include")
        check(transferIncludes.size == 1)
        assertRule(transferIncludes.single(), "database")
    }
}

val verifyV04ReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Verifies the installable v0.4 release candidate identity."

    val configuredVersionCode = android.defaultConfig.versionCode
    val configuredVersionName = android.defaultConfig.versionName
    inputs.property("versionCode", configuredVersionCode)
    inputs.property("versionName", configuredVersionName.orEmpty())

    doLast {
        check(configuredVersionCode == 6) {
            "v0.4 release candidate must use versionCode 6, found $configuredVersionCode."
        }
        check(configuredVersionName == "0.4.0") {
            "v0.4 release candidate must use versionName 0.4.0, found $configuredVersionName."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyV04ReleaseConfiguration)
}

tasks.named("check") {
    dependsOn(verifyBackupPolicy)
}
