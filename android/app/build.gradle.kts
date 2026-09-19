import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}
// Pegamos a chave do local.properties, com um fallback de segurança vazio se não existir
val apiSecretKey: String = localProperties.getProperty("API_SECRET_KEY") ?: "\"\""

android {
    namespace = "com.zangi.chat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zangi.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Injeta a API Key no BuildConfig gerado
        buildConfigField("String", "API_SECRET_KEY", apiSecretKey)
    }

    buildTypes {
        release {
            // IMPORTÃO: Ative o Minify para dificultar a engenharia reversa do seu app em produção
            isMinifyEnabled = true 
            isShrinkResources = true
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
        viewBinding = true
        buildConfig = true // Habilita a geração da classe BuildConfig
    }
}

dependencies {
    // --- CORE & UI ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // --- LIFECYCLE & MVVM ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4") // Adicionado para suporte a ciclo de vida
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // --- COROUTINES (Para operações assíncronas de captura e rede) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // --- NETWORKING (Retrofit & OkHttp) ---
    // Essencial para o envio de dados para o seu backend no Render
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // Adicionado OkHttp core
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- CAMERA X (Módulo de Captura de Imagem) ---
    // Necessário para a captura de fotos silenciosas
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // --- MEDIA & IMAGE PROCESSING ---
    // Para manipular e redimensionar as capturas de tela/fotos antes do upload
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
    implementation("androidx.palette:palette-ktx:1.0.0") // Útil para análise de cores da tela

    // --- WORKMANAGER (Para garantir o upload em background) ---
    // Crucial para que o upload de dados não pare se o usuário fechar o app
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- TESTING ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}