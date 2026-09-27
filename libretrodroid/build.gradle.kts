// LibretroDroid 0.13.2 (github.com/Swordfish90/LibretroDroid, GPLv3) compilado a partir do código,
// com correções para os núcleos que desenham com a GPU. Veja README.md.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.swordfish.libretrodroid"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    externalNativeBuild {
        // Sem versão fixa: o AGP usa o CMake padrão dele e o instala sozinho se faltar, como faz com o NDK.
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines)
}
