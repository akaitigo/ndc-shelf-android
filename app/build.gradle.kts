plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.about.libraries)
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.ndcshelf.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ndcshelf.app"
        minSdk = 23
        targetSdk = 37
        versionCode = 7
        versionName = "0.5.0"

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
                // minSdk 23のためv1も必要。v3はAPK Signature Scheme v3の鍵
                // ローテーション経路を将来利用できるようにするため有効化する。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // 配布物を2種類に分ける（docs/adr/0009-on-device-llm-librarian.md）。
    // standard: 端末内LLMを同梱しない従来のアプリ。
    // ai: LiteRT-LMを同梱する別アプリ。applicationIdが異なるため相互に上書き更新されず、
    //     同時インストールできる。データは共有しない（乗り換えはexport/import）。
    flavorDimensions += "inference"

    productFlavors {
        create("standard") {
            dimension = "inference"
            isDefault = true
        }
        create("ai") {
            dimension = "inference"
            applicationIdSuffix = ".ai"
            versionNameSuffix = "-ai"
        }
    }

    buildTypes {
        debug {
            // en-XA（アクセント付き・約30%長い）とar-XB（RTL）の擬似ロケールを生成し、
            // ScreenshotRegressionTestとRoborazziのgoldenで切れ・未翻訳・双方向表示を検証する。
            // debug限定なのでreleaseのAPK/AABサイズには影響しない。
            isPseudoLocalesEnabled = true
        }
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
        unitTests.all {
            // デモGIFの記録は明示指定時だけ行う。GradleのJVMプロパティは
            // test workerへ自動伝播しないため、ここで転送する。
            it.systemProperty(
                "ndcshelf.recordDemo",
                providers.systemProperty("ndcshelf.recordDemo").getOrElse("false"),
            )
        }
    }

    sourceSets {
        // Robolectric reads migration fixtures from the debug target assets; release stays clean.
        getByName("debug").assets.directories.add("$projectDir/schemas")
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        getByName("test").resources.directories.add("$rootDir/fixtures")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // LiteRT-LMのnative libraryはarm64-v8aだけを同梱する。x86_64はエミュレータ専用で、
        // 配布サイズが約10MB増える一方で実機の対象にならない。非対応ABIでは
        // LlmCapabilityCheckerが取得も起動も抑止する（fail-closed）。
        jniLibs.excludes += "**/x86_64/liblitertlm_jni.so"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

baselineProfile {
    // プロファイルはリポジトリへコミットし、通常ビルドでは再生成しない。
    // 再生成は .github/workflows/benchmark.yml（workflow_dispatch）または
    // ローカルの :app:generateReleaseBaselineProfile で行う。
    automaticGenerationDuringBuild = false
    saveInSrc = true
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
    // 端末内LLMのruntime。aiフレーバーだけへ入り、standardの配布物には含まれない。
    "aiImplementation"(libs.litertlm.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.apache.commons.csv)
    implementation(libs.tink.android)
    implementation(libs.json.canonicalization)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.window.core)
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

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
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
}

/**
 * ライセンスレポートはフレーバーごとに生成する。aiフレーバーだけがLiteRT-LMを
 * 含むため、両方を検証しないと同梱依存の申告漏れに気づけない。
 */
val licenseReportVariants = listOf("standardRelease" to "Standard", "aiRelease" to "Ai")

val generateThirdPartyNoticesTasks =
    licenseReportVariants.map { (variant, suffix) ->
        tasks.register<Copy>("generateThirdPartyNotices$suffix") {
            group = "documentation"
            description = "Generates the third-party notice artifact from $variant dependencies."
            dependsOn("prepareLibraryDefinitions${suffix}Release")
            from(
                layout.buildDirectory.file(
                    "generated/aboutLibraries/$variant/res/raw/aboutlibraries.json",
                ),
            )
            into(layout.buildDirectory.dir("reports/licenses"))
            rename { "THIRD-PARTY-NOTICES-$variant.json" }
        }
    }

/** リリース成果物へ添付する正本。standardの内容をそのまま使う。 */
val generateThirdPartyNotices by tasks.registering(Copy::class) {
    group = "documentation"
    description = "Copies the standard release notice artifact to the published file name."
    dependsOn(generateThirdPartyNoticesTasks)
    from(layout.buildDirectory.file("reports/licenses/THIRD-PARTY-NOTICES-standardRelease.json"))
    into(layout.buildDirectory.dir("reports/licenses"))
    rename { "THIRD-PARTY-NOTICES.json" }
}

val verifyLicenseReport by tasks.registering {
    group = "verification"
    description = "Verifies that both flavors' license reports cover direct and transitive dependencies."
    dependsOn(generateThirdPartyNotices)

    val reports =
        licenseReportVariants.associate { (variant, _) ->
            variant to layout.buildDirectory.file("reports/licenses/THIRD-PARTY-NOTICES-$variant.json")
        }
    inputs.files(reports.values)
    doLast {
        reports.forEach { (variant, report) ->
            val contents = report.get().asFile.readText()
            val libraryCount = "\"uniqueId\":".toRegex().findAll(contents).count()
            check(libraryCount >= 100) {
                "Expected a transitive $variant dependency report, but found only $libraryCount entries."
            }
            check("\"uniqueId\":\"org.apache.commons:commons-csv\"" in contents) {
                "A known direct dependency is missing from the $variant license report."
            }
            check("\"uniqueId\":\"com.squareup.okio:okio-jvm\"" in contents) {
                "A known transitive dependency is missing from the $variant license report."
            }
            check("\"licenses\":[]" !in contents) {
                "At least one $variant dependency has no declared license or terms."
            }
            // 端末内LLM runtimeはaiフレーバーだけに入り、standardの配布物へ漏れてはならない。
            val hasLiteRtLm = "com.google.ai.edge.litertlm:litertlm-android" in contents
            check(hasLiteRtLm == (variant == "aiRelease")) {
                if (variant == "aiRelease") {
                    "The ai release report must declare the bundled LiteRT-LM runtime."
                } else {
                    "The standard release must not bundle the LiteRT-LM runtime."
                }
            }
            mapOf(
                "Apache-2.0" to "Apache License\\nVersion 2.0",
                "BSD-3-Clause" to "Redistribution and use in source and binary forms",
                "MIT" to "MIT License",
            ).forEach { (spdxId, contentMarker) ->
                check("\"spdxId\":\"$spdxId\"" in contents) {
                    "The bundled $spdxId license text is missing from the $variant report."
                }
                check(contentMarker in contents) {
                    "The bundled $spdxId license content is empty in the $variant report."
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyLicenseReport)
}

tasks.cyclonedxDirectBom {
    // 両フレーバーの実行時依存を1つのSBOMへ含め、配布物のどちらも追跡できるようにする。
    includeConfigs = listOf("standardReleaseRuntimeClasspath", "aiReleaseRuntimeClasspath")
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    componentName = "NDC Shelf"
    componentVersion = android.defaultConfig.versionName ?: "unspecified"
    includeLicenseText = false
    jsonOutput = layout.buildDirectory.file("reports/cyclonedx/ndc-shelf.cdx.json")
    xmlOutput.unsetConvention()
}

/**
 * 配布サイズ予算はフレーバーごとに持つ（docs/PERFORMANCE_BUDGETS.md）。
 *
 * - standard: 端末内LLMを同梱しない。実測17,590,996バイト（2026-07-29）+20%を上限とする。
 *   直近の実測は18,450,318バイト（2026-08-01、R8有効・未署名）。
 * - ai: LiteRT-LMのarm64-v8a native libraryを含む。実測28,953,242バイト（2026-08-01）に
 *   約3%の余裕を持たせた30,000,000バイトを上限とする。
 *
 * 予算の引き上げにはmaintainerの承認と上記docの更新を伴うこと。
 */
val releaseBundleBudgets =
    listOf(
        Triple("Standard", "standardRelease/app-standard-release.aab", 21_000_000L),
        Triple("Ai", "aiRelease/app-ai-release.aab", 30_000_000L),
    )

val verifyReleaseBundleSizeTasks =
    releaseBundleBudgets.map { (flavor, path, budgetBytes) ->
        tasks.register("verify${flavor}ReleaseBundleSize") {
            group = "verification"
            description =
                "Verifies the $flavor release AAB stays within the size budget in docs/PERFORMANCE_BUDGETS.md."
            dependsOn("bundle${flavor}Release")

            val bundle = layout.buildDirectory.file("outputs/bundle/$path")
            inputs.file(bundle)
            inputs.property("budgetBytes", budgetBytes)
            doLast {
                val actualBytes = bundle.get().asFile.length()
                check(actualBytes in 1 until budgetBytes) {
                    "$flavor release AAB is $actualBytes bytes, over the $budgetBytes byte budget. " +
                        "Investigate the size regression or update docs/PERFORMANCE_BUDGETS.md " +
                        "with a justified new budget before raising this limit."
                }
                println("$flavor release AAB size: $actualBytes bytes (budget: $budgetBytes bytes)")
            }
        }
    }

/** 両フレーバーのサイズ予算をまとめて判定する入口。 */
val verifyReleaseBundleSize by tasks.registering {
    group = "verification"
    description = "Verifies every flavor's release AAB size budget."
    dependsOn(verifyReleaseBundleSizeTasks)
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

val verifyV05ReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "Verifies the installable v0.5 release candidate identity."

    val configuredVersionCode = android.defaultConfig.versionCode
    val configuredVersionName = android.defaultConfig.versionName
    inputs.property("versionCode", configuredVersionCode)
    inputs.property("versionName", configuredVersionName.orEmpty())

    doLast {
        check(configuredVersionCode == 7) {
            "v0.5 release candidate must use versionCode 7, found $configuredVersionCode."
        }
        check(configuredVersionName == "0.5.0") {
            "v0.5 release candidate must use versionName 0.5.0, found $configuredVersionName."
        }
    }
}

tasks.named("check") {
    dependsOn(verifyV05ReleaseConfiguration)
}

tasks.named("check") {
    dependsOn(verifyBackupPolicy)
}
