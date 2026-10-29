import java.util.Properties
import java.net.URI

plugins {
    id("com.android.application")
}

// Private local opt-in; public builds have no AI cleartext exception.
val localAiProperties = Properties().apply {
    if (!providers.gradleProperty("publicAiConfig").map { it == "true" }.getOrElse(false)) {
        rootProject.file("local-ai.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
}
val localAiBaseUrl = localAiProperties.getProperty("ai.baseUrl", "").trim().trimEnd('/')
val localAiUri = if (localAiBaseUrl.isEmpty()) null else runCatching { URI(localAiBaseUrl) }.getOrNull()
require(localAiBaseUrl.isEmpty() || (localAiUri != null && localAiUri.scheme == "http" &&
    localAiUri.host != null && localAiUri.host.matches(Regex("[A-Za-z0-9.:-]+")) &&
    localAiUri.rawUserInfo == null && localAiUri.rawQuery == null && localAiUri.rawFragment == null &&
    (localAiUri.port == -1 || localAiUri.port in 1..65535) && localAiBaseUrl.length <= 2048)) {
    "Invalid private local AI endpoint configuration"
}
val generatedNetworkSecurity = layout.buildDirectory.dir("generated/localAi/res")
val generateLocalAiNetworkSecurity by tasks.registering {
    inputs.property("localAiBaseUrl", localAiBaseUrl)
    inputs.file("src/main/res/xml/network_security_config.xml")
    outputs.dir(generatedNetworkSecurity)
    doLast {
        val template = file("src/main/res/xml/network_security_config.xml").readText()
        val domain = localAiUri?.host?.let { "        <domain includeSubdomains=\"false\">$it</domain>\n" } ?: ""
        generatedNetworkSecurity.get().file("xml/network_security_config.xml").asFile.apply {
            parentFile.mkdirs()
            writeText(template.replace("    </domain-config>", domain + "    </domain-config>"))
        }
    }
}
tasks.named("preBuild").configure { dependsOn(generateLocalAiNetworkSecurity) }
android {
    namespace = "com.qylent.manhwatakip"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    sourceSets.getByName("debug").res.srcDir(generatedNetworkSecurity.get().asFile)
    sourceSets.getByName("release").res.srcDir(generatedNetworkSecurity.get().asFile)
    defaultConfig {
        applicationId = "com.qylent.manhwatakip"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.10.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LOCAL_AI_BASE_URL", "\"" + localAiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        animationsDisabled = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("org.aomedia.avif.android:avif:1.3.0.841110fd")
    implementation("androidx.activity:activity-ktx:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20180813")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
