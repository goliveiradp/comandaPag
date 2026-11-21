plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "br.com.comandapag"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.comandapag"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    flavorDimensions += "terminal"
    productFlavors {
        // Roda na maquininha PagBank Smart 2, cobrando de verdade pelo PlugPag.
        create("smart") {
            dimension = "terminal"
        }
        // Roda no emulador ou no celular, com uma maquininha simulada.
        create("simulador") {
            dimension = "terminal"
            applicationIdSuffix = ".simulador"
            versionNameSuffix = "-simulador"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Arquivos repetidos nas bibliotecas do Ktor (servidor/cliente da rede local)
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Esquemas exportados do banco, lidos pelos testes de migração (só na versão debug)
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")
}

ksp {
    // Guarda o esquema do banco a cada versão (usado para conferir as migrações)
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":rede"))
    // QR Code do DANFE NFC-e
    implementation("com.google.zxing:core:3.5.3")

    // PagBank PlugPag: só no flavor que roda na maquininha
    "smartImplementation"("br.com.uol.pagseguro.plugpagservice.wrapper:wrapper:1.35.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    val room = "2.7.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // Testes de interface com Robolectric (rodam na JVM, sem emulador)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.room:room-testing:$room")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
