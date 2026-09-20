plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

group = "com.hiczp"
version = "0.0.1"

kotlin {
    listOf(
        linuxX64(),
        linuxArm64(),
        mingwX64(),
        macosArm64(),
    ).forEach {
        it.binaries.executable {
            entryPoint = "com.hiczp.factorio.mcp.main"
        }
    }
}
