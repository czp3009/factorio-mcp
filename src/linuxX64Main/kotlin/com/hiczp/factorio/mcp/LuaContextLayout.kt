@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxScriptLayout
import kotlinx.cinterop.set
import kotlin.math.abs

/** The default script selection obtained by specializing the native command's empty-string path, without calling it. */
internal data class LuaContextLayout(
    val size: Long,
    val script: FirstNamedPointer,
    val stringData: Long,
    val stringLength: Long
) {
    fun writeTo(output: FmLinuxScriptLayout, element: LuaScriptLayout, loadBias: Long) {
        require(
            loadBias >= 0 && loadBias % 8 == 0L && element.vtable >= 16 &&
                    listOf(
                        element.vtable,
                        element.typeInfo
                    ).all { it > 0 && it % 8 == 0L && it <= Long.MAX_VALUE - 16 - loadBias })
        require(
            size in 8..(64 * 1024 * 1024) && element.size in 8..(64 * 1024 * 1024) &&
                    script.expected.size in 1..64 && script.begin in 0..size - 8 && script.end in 0..size - 8 &&
                    script.name >= 0 && stringData in 0..4096 && stringLength in 0..4096 &&
                    script.name <= element.size - maxOf(
                stringData,
                stringLength
            ) - 8 && element.state in 0..element.size - 8
        )
        val members = listOf(script.name + stringData, script.name + stringLength, element.state).sorted()
        require(
            members.first() >= 8 && script.begin >= 8 && script.end >= 8 &&
                members.zipWithNext()
                    .all { (first, second) -> second - first >= 8 } && abs(script.begin - script.end) >= 8)
        val flags = listOf(element.readiness.loading, element.readiness.enabled)
        require(flags.distinct().size == 2 && flags.all { flag ->
            flag in 8 until element.size && members.none { flag in it until it + 8 }
        }) { "Script load-handler flags overlap another member or exceed the object" }
        output.vtable = (element.vtable + loadBias).toULong()
        output.typeInfo = (element.typeInfo + loadBias).toULong()
        output.contextSize = size.toUInt()
        output.scriptSize = element.size.toUInt()
        output.begin = script.begin.toUInt()
        output.end = script.end.toUInt()
        output.name = script.name.toUInt()
        output.stringData = stringData.toUInt()
        output.stringLength = stringLength.toUInt()
        output.expectedSize = script.expected.size.toUInt()
        script.expected.forEachIndexed { index, byte -> output.expected[index] = byte.toUByte() }
        output.state = element.state.toUInt()
        output.loading = element.readiness.loading.toUInt()
        output.enabled = element.readiness.enabled.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage, scriptSize: Long): LuaContextLayout {
            val size = SysVObjectSize.resolve(image, "10LuaContext")
            fun stringMember(method: String): Long {
                val accessor = SysVAccessors.resolve(
                    image,
                    image.symbol("_ZNKSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE${method}Ev")
                )
                require(accessor.width == 8 && accessor.mask == ULong.MAX_VALUE && accessor.shift == 0)
                return accessor.offset
            }

            val data = stringMember("4data")
            val length = stringMember("4size")
            val caller =
                image.symbol("_ZN10LuaContext13runLuaCommandERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEERK13CommandSourcePK17RemoteInterfaceIDb")
            EhFrames(image).function(caller)
            val selection = SysVFirstNamedPointer.resolve(
                image.functionBytes(caller, 8192), caller.address, size, scriptSize,
                data, length, ArgumentScalar(6, length, 8, 0)
            )
            return LuaContextLayout(size, selection, data, length)
        }
    }
}
