plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.smbj)
    implementation(libs.sqldelight.runtime)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.sqldelight.sqlite.driver)
}

sqldelight {
    databases {
        create("LibraryDatabase") {
            packageName = "com.slogan.tv.library.db"
        }
    }
}

tasks.test {
    useJUnit()
}
