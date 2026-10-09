plugins { id("com.android.application") }

android {
    namespace = "dev.agentm.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.agentm.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/webAssets").get().asFile)
}

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
tasks.named("preBuild") { dependsOn(syncWorkbench, verifyRuntimeAssets) }

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation(project(":terminal-view"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
