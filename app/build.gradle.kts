import java.net.URI
import java.util.Properties
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Assinatura da release: keystore.properties na raiz (fora do git) ou variáveis de ambiente
// RETROVIKA_KEYSTORE, RETROVIKA_KEYSTORE_PASSWORD, RETROVIKA_KEY_ALIAS e RETROVIKA_KEY_PASSWORD.
// Sem nenhum dos dois, a release sai assinada com a chave de debug (serve para testes, não para publicar).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)
val releaseStoreFile = signingValue("storeFile", "RETROVIKA_KEYSTORE")

android {
    namespace = "com.retrovika.app"
    compileSdk = 36
    // O mesmo NDK do :libretrodroid: é ele que tira os símbolos de depuração das .so no empacotamento.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.retrovika.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.2.3"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Extrator nativo de .7z (src/main/cpp): o dicionário do LZMA fica fora do limite do heap Java.
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "RETROVIKA_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RETROVIKA_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RETROVIKA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseStoreFile != null) "release" else "debug")
        }
    }

    testOptions {
        // Os testes de unidade rodam na JVM: chamadas a android.* devolvem valores padrão em vez de falhar.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Só os idiomas do app: descarta as traduções extras das bibliotecas.
    androidResources { localeFilters += listOf("en", "pt") }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.datastore)
    implementation(libs.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.jsoup)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    implementation(project(":libretrodroid"))

    testImplementation(libs.junit)
}

if (releaseStoreFile == null) {
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
        doFirst { logger.warn("Retrovika: keystore.properties ausente; a release foi assinada com a chave de debug.") }
    }
}

// ---------------------------------------------------------------------------------------------
// Núcleos embutidos (opcional)
//
//   ./gradlew assembleRelease -PbundleCores=all                 -> embute todos os núcleos
//   ./gradlew assembleRelease -PbundleCores=snes9x,mgba,fceumm  -> apenas os escolhidos
//   ./gradlew assembleRelease -PbundleAbis=arm64-v8a            -> limita as arquiteturas
//
// Sem a propriedade, o APK fica leve e cada núcleo é baixado na primeira vez que for usado.
// ---------------------------------------------------------------------------------------------
val bundleCoresProp = providers.gradleProperty("bundleCores").orNull
val bundleAbis = providers.gradleProperty("bundleAbis").orNull?.split(",") ?: listOf("arm64-v8a")
val bundledCoresDir = layout.buildDirectory.dir("generated/bundledCores/jniLibs")

val fetchCores by tasks.registering {
    description = "Baixa núcleos libretro do buildbot para empacotar no APK."
    val systemsFile = file("src/main/java/com/retrovika/app/core/systems/Systems.kt")
    inputs.file(systemsFile)
    inputs.property("cores", bundleCoresProp ?: "")
    inputs.property("abis", bundleAbis.joinToString())
    outputs.dir(bundledCoresDir)
    doLast {
        val all = Regex("""CoreInfo\(\s*"([a-z0-9_]+)"""").findAll(systemsFile.readText()).map { it.groupValues[1] }.toSortedSet()
        val wanted = if (bundleCoresProp == "all") all else bundleCoresProp!!.split(",").map { it.trim() }.toSortedSet()
        val outRoot = bundledCoresDir.get().asFile
        bundleAbis.forEach { abi ->
            val dir = File(outRoot, abi).apply { mkdirs() }
            for (core in wanted) {
                val target = File(dir, "lib${core}_libretro_android.so")
                if (target.exists()) continue
                val url = URI("https://buildbot.libretro.com/nightly/android/latest/$abi/${core}_libretro_android.so.zip").toURL()
                logger.lifecycle("Baixando $core ($abi)…")
                runCatching {
                    ZipInputStream(url.openStream().buffered()).use { zis ->
                        generateSequence { zis.nextEntry }.first { e -> e.name.endsWith(".so") }
                        target.outputStream().use { zis.copyTo(it) }
                    }
                }.onFailure { e -> logger.warn("  ! $core indisponível para $abi: ${e.message}") }
            }
        }
    }
}

if (bundleCoresProp != null) {
    android.sourceSets.getByName("main").jniLibs.srcDir(bundledCoresDir)
    tasks.named("preBuild") { dependsOn(fetchCores) }
}
