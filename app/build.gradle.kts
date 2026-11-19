import java.util.regex.Pattern

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.linbox"
    compileSdk = 34
    ndkVersion = ""

    defaultConfig {
        // 独立浏览器：可与 LinBox 共存
        applicationId = "com.linbox.browser"
        minSdk = 24
        targetSdk = 28
        versionCode = 5
        versionName = "1.2.2"

        // 无原生代码（termux PTY / DAC 桥已随容器栈移除）
        ndk {
            abiFilters.clear()
        }
    }

    // ruffle 资产按白名单打包（过滤旧版双引擎死重，与 LinBox 同规则）
    val ruffleWhitelist = listOf(
        "ruffle.js",
        "core.ruffle.831c4f4a93befb9e84af.js",
        "core.ruffle.0875e44536e955474b0c.js",
        "1ef41ff58c9763bed027.wasm",
        "63468f5322aed2e768a8.wasm",
        "simhei.ttf"
    )
    val ruffleDir = layout.projectDirectory.dir("src/main/assets/ruffle")
    val ruffleStage = layout.buildDirectory.dir("generated/ruffleAssets")

    val prepareRuffleAssets by tasks.registering {
        doLast {
            val outDir = ruffleStage.get().asFile
            outDir.mkdirs()
            val src = ruffleDir.asFile
            if (src.isDirectory) {
                src.listFiles()?.forEach { f ->
                    if (ruffleWhitelist.contains(f.name)) {
                        f.copyTo(File(outDir, f.name), overwrite = true)
                    }
                }
            }
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(ruffleStage)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 书签 / 历史记录
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
