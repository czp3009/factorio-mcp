@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.EventLayout
import kotlinx.cinterop.*

/** Fresh event storage is described by the selected PDB, never by a remembered C++ layout. */
internal class EventLayouts(types: DebugTypes) {
    private val mouse = "agui::MouseEvent"
    private val keyboard = "agui::KeyEvent"

    init {
        types.plainMembers("agui::Point", setOf("x", "y"))
        types.plainMembers(
            mouse,
            setOf(
                "position",
                "mouseWheelChange",
                "button",
                "eventType",
                "timeStamp",
                "isAlt",
                "isControl",
                "isShift",
                "source",
                "previous",
            ),
        )
        types.plainMembers(
            keyboard,
            setOf(
                "unichar",
                "timeStamp",
                "keyCode",
                "extKey",
                "key",
                "isAlt",
                "isControl",
                "isShift",
                "isMeta",
                "handled",
                "source",
            ),
        )
    }

    private val values = buildList {
        add(types.size(mouse))
        val point =
            types.namedMember(mouse, "position", "agui::Point", types.size("agui::Point").toULong())
        add(point + types.scalarMember("agui::Point", "x", 4u, 6u))
        add(point + types.scalarMember("agui::Point", "y", 4u, 6u))
        add(types.scalarMember(mouse, "mouseWheelChange", 4u, 6u))
        add(types.namedMember(mouse, "button", "agui::MouseButton", 2u))
        add(types.namedMember(mouse, "eventType", "agui::MouseEvent::Type", 4u))
        add(types.scalarMember(mouse, "timeStamp", 8u, 8u))
        listOf("isAlt", "isControl", "isShift").forEach { add(types.byteMember(mouse, it, true)) }
        listOf("source", "previous").forEach { add(types.pointerMember(mouse, it, "agui::Widget")) }
        add(types.size(keyboard))
        add(types.scalarMember(keyboard, "unichar", 4u, 7u))
        add(types.scalarMember(keyboard, "timeStamp", 8u, 8u))
        add(types.scalarMember(keyboard, "keyCode", 4u, 6u))
        add(types.namedMember(keyboard, "extKey", "agui::ExtendedKeyEnum", 4u))
        add(types.namedMember(keyboard, "key", "agui::KeyEnum", 4u))
        listOf("isAlt", "isControl", "isShift", "isMeta", "handled").forEach {
            add(types.byteMember(keyboard, it, true))
        }
        add(types.pointerMember(keyboard, "source", "agui::Widget"))
        listOf("LEFT", "RIGHT", "MIDDLE").forEach { add(types.enumValue("agui::MouseButton", it)) }
        listOf("MOUSE_ENTER", "MOUSE_DOWN", "MOUSE_UP", "MOUSE_LEAVE").forEach {
            add(types.enumValue("agui::MouseEvent::Type", it))
        }
        listOf("KEY_NONE", "KEY_A", "KEY_BACKSPACE").forEach {
            add(types.enumValue("agui::KeyEnum", it))
        }
        add(types.scalarMember("agui::Widget", "usageBitMask", 4u, 7u))
        add(types.enumValue("agui::Widget", "FIRE_CLICK_ON_MOUSE_DOWN"))
        add(types.enumValue("agui::ExtendedKeyEnum", "EXT_KEY_NONE"))
        add(
            types.pointerPath(
                "agui::Gui",
                "controlWithLock",
                "target",
                target = "agui::GenericTargetable",
                indirections = 1,
            )
        )
        add(types.directBaseOffset("agui::Widget", "agui::GenericTargetable"))
    }

    fun write(target: EventLayout) {
        check(values.size * sizeOf<UIntVar>() == sizeOf<EventLayout>()) {
            "Event adapter wire shape differs"
        }
        // This is our own wire structure; game offsets above all come from DbgHelp.
        val fields = target.ptr.reinterpret<UIntVar>()
        values.forEachIndexed { index, value -> fields[index] = value }
    }
}
