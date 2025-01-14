import org.gradle.api.JavaVersion.VERSION_1_7
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        mavenCentral()
    }

    dependencies {
        classpath(kotlinModule("gradle-plugin", kotlin_version))
    }
}

plugins {
    application
    kotlin("jvm") version "1.6.20"
}

apply {
    plugin("kotlin")
}

application {
    mainClassName = "samples.HelloWorld"
}

repositories {
    jcenter()
}

dependencies {
    testCompile("junit:junit:4.12")
    implementation(kotlin("stdlib-jdk8"))
}

// VERSION: 1.6.20
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}