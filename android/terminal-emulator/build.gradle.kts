plugins { id("com.android.library") }
android {
    namespace = "com.termux.terminal"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { cmake { path = file("src/main/jni/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
