plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.citrusd365.autopulse"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.citrusd365.autopulse"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Required by F-Droid: disable proprietary Google Play dependency metadata block in APKs
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)
}

// Local development convenience hook (runs only on Debug builds in Android Studio, never on F-Droid Release builds)
tasks.configureEach {
    if (name == "packageDebug") {
        doLast {
            val distDir = rootProject.file("dist")
            distDir.mkdirs()
            val apkCandidates = layout.buildDirectory.get().asFile.walkTopDown()
                .filter { it.isFile && it.extension == "apk" && !it.name.contains("unsigned") }
                .sortedByDescending { it.lastModified() }
                .toList()
            apkCandidates.firstOrNull()?.let { latestApk ->
                latestApk.copyTo(File(distDir, "AutoPulse-Routines-v1.0.0.apk"), overwrite = true)
            }

            // Automatically grant WRITE_SECURE_SETTINGS and keep Accessibility Service enabled
            // on the connected phone whenever Android Studio builds/runs the debug app on Windows
            runCatching {
                val localPropsFile = rootProject.file("local.properties")
                val sdkDir = if (localPropsFile.exists()) {
                    localPropsFile.readLines()
                        .firstOrNull { it.trim().startsWith("sdk.dir=") }
                        ?.substringAfter("sdk.dir=")
                        ?.trim()
                        ?.replace("\\:", ":")
                        ?.replace("\\\\", "\\")
                } else {
                    null
                }
                if (!sdkDir.isNullOrBlank()) {
                    val adbExe = File(sdkDir, "platform-tools/adb.exe")
                    if (adbExe.exists()) {
                        val adbPath = adbExe.absolutePath
                        val pkg = "com.citrusd365.autopulse"
                        val svc = "$pkg/$pkg.service.AutomationAccessibilityService"
                        val psScript = """
                            for (${'$'}i = 0; ${'$'}i -lt 5; ${'$'}i++) {
                                & '$adbPath' shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>${'$'}null
                                ${'$'}cur = (& '$adbPath' shell settings get secure enabled_accessibility_services 2>${'$'}null)
                                if (${'$'}null -ne ${'$'}cur) { ${'$'}cur = ${'$'}cur.Trim() }
                                if ([string]::IsNullOrEmpty(${'$'}cur) -or ${'$'}cur -eq 'null') {
                                    & '$adbPath' shell settings put secure enabled_accessibility_services '$svc' 2>${'$'}null
                                } elseif (${'$'}cur -notlike '*$pkg*') {
                                    & '$adbPath' shell settings put secure enabled_accessibility_services "${'$'}cur`:$svc" 2>${'$'}null
                                }
                                & '$adbPath' shell settings put secure accessibility_enabled 1 2>${'$'}null
                                Start-Sleep -Seconds 3
                            }
                        """.trimIndent()
                        ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psScript)
                            .start()
                    }
                }
            }
        }
    }
}
