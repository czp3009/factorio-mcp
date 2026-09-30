package com.hiczp.factorio.mcp

/** Binding codes are compared unchanged with the validated SDL-derived native mouse event payload. */
internal object ControlMouseCodes {
    fun resolve(image: ElfImage, extent: Long, type: Long, code: Long, kind: Int): Map<Int, String> {
        val conversion = SdlButtonConversion.resolve(image)
        val event = image.symbol("_ZN5Event15convertSDLEventERKNS_5StateERK9SDL_EventR12CompactDequeIS_Ef")
        // SDL2's public mouse-button identifiers, not Factorio values or positions.
        // https://github.com/libsdl-org/SDL/blob/SDL2/include/SDL_mouse.h
        val sdk = mapOf(1 to "left", 3 to "right", 2 to "middle", 4 to "button_4", 5 to "button_5")
        SdlButtonAdmission.buttonPaths(X64ControlFlow.resolve(image, event), conversion.admission.table, sdk.keys)
        ControlEventCode.resolve(
            image,
            extent,
            type,
            code,
            kind,
            conversion.header.extent.toLong(),
            conversion.payload.code
        )
        val result = sdk.map { (button, name) ->
            val native = checkNotNull(conversion.admission.table.value(button))
            require(native in 0..Int.MAX_VALUE.toLong())
            native.toInt() to name
        }
        require(result.map { it.first }.toSet().size == sdk.size)
        return result.toMap()
    }
}
