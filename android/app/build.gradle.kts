plugins { id("com.android.application") }

val releaseStorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "dev.agentm.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.agentm.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 19
        versionName = "0.14.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets").get().asFile)
}

val validateReleaseSigning by tasks.registering {
    doLast {
        check(hasReleaseSigning) {
            "Release signing requires ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD. See docs/20-GitHub发布.md."
        }
        check(file(releaseStorePath!!).isFile) { "Release keystore does not exist." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseSigning) }

val npm = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
val buildWorkbench by tasks.registering(Exec::class) {
    workingDir(rootProject.file("../uiux-design"))
    commandLine(npm, "run", "build")
    inputs.dir(rootProject.file("../uiux-design/src"))
    inputs.files(rootProject.file("../uiux-design/package.json"), rootProject.file("../uiux-design/package-lock.json"), rootProject.file("../uiux-design/tsconfig.json"), rootProject.file("../uiux-design/vite.config.ts"), rootProject.file("../uiux-design/index.html"))
    inputs.dir(rootProject.file("../uiux-design/public"))
    outputs.dir(rootProject.file("../uiux-design/dist"))
}
val syncWorkbench by tasks.registering(Sync::class) {
    dependsOn(buildWorkbench)
    from(rootProject.file("../uiux-design/dist"))
    into(layout.buildDirectory.dir("generated/webAssets/workbench"))
}
val verifyRuntimeAssets by tasks.registering(Exec::class) {
    commandLine("node", rootProject.file("../tools/prepare-linux-runtime.mjs").absolutePath)
}
val verifyWebCompatibility by tasks.registering(Exec::class) {
    commandLine("node", rootProject.file("../tools/opencode-web-compat/verify.mjs").absolutePath)
}
tasks.named("preBuild") { dependsOn(syncWorkbench, verifyRuntimeAssets, verifyWebCompatibility) }

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation(project(":terminal-view"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tomlj:tomlj:1.1.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
