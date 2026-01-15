@file:Suppress("UnstableApiUsage")
import com.android.build.api.dsl.VariantDimension
import com.heyanle.buildsrc.Android
import com.heyanle.buildsrc.RoomSchemaArgProvider
import java.util.Properties

plugins {
    alias(build.plugins.android.application)
    alias(build.plugins.kotlin.android)
    alias(build.plugins.ksp)
}



val publishingProps = Properties()
runCatching {
    publishingProps.load(project.rootProject.file("publishing/publishing.properties").inputStream())
}.onFailure {
    // it.printStackTrace()
}

android {
    namespace =  "com.heyanle.easybangumi4"
    compileSdk = Android.compileSdk

    defaultConfig {

        val keystoreFile = project.rootProject.file("local.properties")
        val properties = Properties()
        properties.load(keystoreFile.inputStream())

        val encKey = properties.getProperty("enc_key") ?: ""

        buildConfigField("String", "ENC_KEY", "\"${encKey}\"")

        applicationId = "com.refgd.easybangumi4"
        minSdk = Android.minSdk
        targetSdk = Android.targetSdk
        versionCode = Android.versionCode
        versionName = Android.versionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        manifestPlaceholders["label_res"] = "@string/the_app_name"
        manifestPlaceholders["is_release"] = true

        ksp {
            arg("room.generateKotlin", "true")
            arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
        }

    }

    sourceSets {
        // Adds exported schema location as test app assets.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }


    packaging {
        resources.excludes.add("META-INF/beans.xml")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")

            resValue("string", "the_app_name", "纯纯看看 Debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
        )
    }
    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = build.versions.compose.compiler.get()
    }

}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
}

dependencies {

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(androidx.bundles.core)
    implementation(libs.androidx.lifecycle.service)
    androidTestImplementation(androidx.bundles.test.core)

    implementation(libs.splitties.appctx)
    implementation(libs.splitties.systemservices)

    //webServer
    implementation(libs.nanohttpd.nanohttpd)
    implementation(libs.nanohttpd.websocket)

    //liveEventBus
    implementation(libs.liveeventbus)

    implementation(androidx.bundles.room.impl)
    implementation(androidx.room.paging)
    annotationProcessor(androidx.room.compiler)
    ksp(androidx.room.compiler)
    testImplementation(androidx.room.testing)
    androidTestImplementation(androidx.room.testing)

    implementation(androidx.preference.ktx)

    implementation(androidx.medie)

    implementation(androidx.google.material)

    implementation(androidx.webkit)

    implementation(androidx.window)

    implementation(androidx.paging.common)
    implementation(androidx.paging.compose)
    implementation(androidx.paging.runtime.ktx)

    implementation(compose.bundles.ui)
    implementation(compose.bundles.runtime)
    implementation(compose.bundles.animation)
    implementation(compose.bundles.foundation)
    implementation(compose.bundles.material)
    implementation(compose.bundles.material3)

    implementation(libs.bundles.okhttp3)
    //implementation(libs.bundles.appcenter)

    implementation(libs.jsoup)
    implementation(libs.gson)
    implementation(libs.moshi)

    implementation(libs.danmaku.render.engine)

    //debugImplementation(libs.leakcanary)

    implementation(libs.glide)
    implementation(libs.okkv2)

    testImplementation(libs.junit)

//    implementation(libs.accompanist.systemuicontroller)
//    implementation(libs.accompanist.swiperefresh)
    implementation(libs.accompanist.permissions)
    implementation(libs.navigtion.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.commons.text)
    implementation(libs.compose.reorderable)

    implementation(libs.koin.core)
    implementation(libs.koin.android)

    implementation(libs.ktor.core)
    implementation(libs.ktor.android)

    // implementation(project(":easy-dlna"))
    implementation(project(":easy-crasher"))
    implementation(project(":easy-i18n"))
    implementation(project(":inject"))
    implementation(project(":lib_upnp"))
//    implementation(project(":gpu_image"))
    //implementation(project(":lib_signal"))

    implementation(libs.zip4j)

    // fimplementation(gecko.gecko)

    implementation(libs.aria.m3u8)
    implementation(libs.aria.compiler)
    implementation(libs.aria)

    implementation(libs.jeff.m3u8)


    implementation(project(":EasyPlayer2:easyplayer2"))

    implementation(project(":EasyMediaTransformer:easy_transformer"))

    implementation(libs.uni.file)

}