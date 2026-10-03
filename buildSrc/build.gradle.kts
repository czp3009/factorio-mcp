plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.native.utils)
    implementation(libs.kotlinpoet)
    implementation(libs.serialization.json)
}
