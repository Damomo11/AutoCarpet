plugins {
    id("net.fabricmc.fabric-loom") version "1.17.17"
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val fabricLoaderVersion = providers.gradleProperty("fabric_loader_version").get()
val jdkVersion = providers.gradleProperty("jdk_version").get().toInt()
val modVersion = providers.gradleProperty("mod_version").get()

base {
    archivesName = "autocarpet"
    group = "com.autocarpet"
    version = modVersion
}

repositories {
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    mavenCentral()
    maven { url = uri("https://babbaj.github.io/maven/") }
    maven { url = uri("https://maven.terraformersmc.com/releases/") }
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")
    implementation("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
    compileOnly("net.fabricmc.fabric-api:fabric-api:0.152.1+26.1.2")
    compileOnly(files("/c/Users/29452/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-key-binding-api-v1/1.1.7+4fc5413f53/ed04f7fd9fc8291a6baf4310c8d44085e7c76f84/fabric-key-binding-api-v1-1.1.7+4fc5413f53.jar".replace("/c/", "C:/")))
    compileOnly(files("C:/Users/29452/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-lifecycle-events-v1/4.1.1+df84eb3d4c/2b9fe06c9552e45f03c18d177c8c71e879655eb/fabric-lifecycle-events-v1-4.1.1+df84eb3d4c.jar"))
        compileOnly("dev.babbaj:nether-pathfinder:1.4.1")
    compileOnly(files("libs/litematica-fabric-26.1.2-0.27.12.jar", "libs/malilib-fabric-26.1.2-0.28.11.jar"))
    include("dev.babbaj:nether-pathfinder:1.4.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly(files("libs/fabric-api-base-2.0.3+ece063234c.jar", "libs/fabric-key-mapping-api-v1-2.0.4+e2bdee784c.jar", "libs/fabric-lifecycle-events-v1-4.1.1+df84eb3d4c.jar"))
    compileOnly("net.fabricmc.fabric-api:fabric-networking-api-v1:4.3.0+ece063234c")
    compileOnly("com.terraformersmc:modmenu:18.0.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "1000"))
}
