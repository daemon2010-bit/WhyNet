plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("runEngineUnitTests") {
    group = "verification"
    description = "Runs DiagnosisEngineTest directly via JUnitCore (ASCII-safe on Windows)."
    dependsOn("testClasses")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.junit.runner.JUnitCore")
    args("dev.whynet.core.engine.DiagnosisEngineTest")
}
