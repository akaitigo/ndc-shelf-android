plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "dev.ndcshelf.baselineprofile"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Macrobenchmark and baseline profile collection require API 28+.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // :app は inference フレーバー（standard / ai）を持つ。ベンチマークと
        // baseline profile は配布の主体である standard だけを対象にする。
        missingDimensionStrategy("inference", "standard")
    }

    targetProjectPath = ":app"

    testOptions.managedDevices.localDevices {
        // CIのworkflow_dispatch（.github/workflows/benchmark.yml）から使うGradle Managed Device。
        // KVMのないローカル環境での実行は必須ではない。
        create("pixel7Api35") {
            device = "Pixel 7"
            apiLevel = 35
            systemImageSource = "aosp"
        }
    }
}

baselineProfile {
    managedDevices += "pixel7Api35"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
