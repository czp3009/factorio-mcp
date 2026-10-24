package com.hiczp.factorio.mcp

/**
 * Public SDL3 ABI. Each consumer must connect these fields to the decoded original SDK argument.
 * https://github.com/libsdl-org/SDL/blob/release-3.2.28/include/SDL3/SDL_events.h
 */
internal object SdlEvents {
    const val CONVERT = "_ZN5Event15convertSDLEventERK9SDL_EventR12CompactDequeIS_Ef"
    const val MOUSE_EMPLACE =
        "_ZN12CompactDequeI5EventE12emplace_backIJRdNS0_4TypeE10MouseEventEEERS0_DpOT_"
    const val ARGUMENT = 7
    const val TYPE = 0L
    const val TIMESTAMP = 8L
    const val KEYBOARD_EXTENT = 40L
    const val KEY_CODE = 28L
    const val KEY_MODIFIERS = 32L
    const val KEY_REPEAT = 37L
    const val KEY_DOWN = 0x300L
    const val KEY_UP = 0x301L
    const val BUTTON_EXTENT = 40L
    const val MOTION_EXTENT = 48L
    const val WHEEL_EXTENT = 56L
    const val WINDOW_EXTENT = 32L
    const val MOUSE_WHICH = 20L
    const val BUTTON = 24L
    const val MOUSE_X = 28L
    const val MOUSE_Y = 32L
    const val WHEEL_X = 44L
    const val WHEEL_Y = 48L
    const val MOTION = 0x400L
    const val BUTTON_DOWN = 0x401L
    const val BUTTON_UP = 0x402L
    const val WHEEL = 0x403L
    const val WINDOW_ENTER = 0x20cL
    const val WINDOW_LEAVE = 0x20dL

    fun field(offset: Long, width: Int) =
        SysVArgumentFlow.Read(SysVArgumentFlow.Reference(ARGUMENT, offset), width)
}
