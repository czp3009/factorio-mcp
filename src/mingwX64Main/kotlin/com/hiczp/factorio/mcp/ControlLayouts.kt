@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.ControlLayout
import com.hiczp.factorio.mcp.nativebridge.FmControl
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString

/** All field paths and numeric vocabularies come from the selected executable's PDB. */
internal class ControlLayouts(types: DebugTypes) {
    private val vector = "std::vector<ControlInput *,std::allocator<ControlInput *> >"
    private val config = "SimpleConfigItem<ControlInputValue>"
    private val string = "std::basic_string<char,std::char_traits<char>,std::allocator<char> >"
    private val stringSize = types.aggregateSize(string).also { check(it in 1uL..256uL) }
    private val first =
        types.pointerPath(
            vector,
            "_Mypair",
            "_Myval2",
            "_Myfirst",
            target = "ControlInput",
            indirections = 2,
        )
    private val last =
        types.pointerPath(
            vector,
            "_Mypair",
            "_Myval2",
            "_Mylast",
            target = "ControlInput",
            indirections = 2,
        )
    private val slots =
        listOf(
            "keyboardAndMouseInput1",
            "keyboardAndMouseInput2",
            "gameControllerInput1",
            "gameControllerInput2",
        )
            .map { types.namedMember("ControlInput", it, config, types.aggregateSize(config)) }
    private val key = types.path(config, "key", target = string, bytes = stringSize)
    private val value =
        types.path(
            config,
            "value",
            target = "ControlInputValue",
            bytes = types.aggregateSize("ControlInputValue"),
        )
    private val linked = types.pointerMember("ControlInput", "linkedGameControl", "ControlInput")
    private val custom =
        types.pointerMember("ControlInput", "customInputPrototype", "CustomInputPrototype")
    private val gui = types.byteMember("ControlInput", "guiInput", true)
    private val usage = types.namedMember("ControlInput", "usageType", "ControlUsageType", 4uL)
    private val type =
        types.namedMember("ControlInputValue", "type", "ControlInputValue::Type", 1uL)
    private val code = types.namedMember("ControlInputValue", "scancode", "SDL_Scancode", 4uL)
    private val modifiers = types.byteMember("ControlInputValue", "modifiers", false)
    private val enabled = types.byteMember("CustomInputPrototype", "enabled", true)
    private val spectating =
        types.byteMember("CustomInputPrototype", "enabledWhileSpectating", true)
    private val cutscene = types.byteMember("CustomInputPrototype", "enabledWhileInCutscene", true)
    private val mouseValue = types.scalarMember("ControlInputValue::MouseButton", "value", 4uL, 7u)
    private val bindingTypes =
        types.enumValues("ControlInputValue::Type").entries.associate { it.value to it.key }
    private val keys =
        types.enumValues("SDL_Scancode").entries.associate {
            it.value to it.key.removePrefix("SDL_SCANCODE_")
        }
    private val wheels =
        types.enumValues("ControlInputValue::MouseWheel").entries.associate {
            it.value to it.key.removePrefix("MouseWheel").lowercase()
        }
    private val usages =
        types.enumValues("ControlUsageType").entries.associate { it.value to it.key }
    private val controllerButtons =
        types.enumValues("SDL_GameControllerButton").entries.associate {
            it.value to it.key.removePrefix("SDL_CONTROLLER_BUTTON_")
        }
    private val controllerAxes =
        types.enumValues("SDL_GameControllerAxis").entries.associate {
            it.value to it.key.removePrefix("SDL_CONTROLLER_AXIS_")
        }
    private val controllerSticks =
        types.enumValues("ControllerStick").entries.associate { it.value to it.key }

    init {
        listOf(
            "mouseButton" to "ControlInputValue::MouseButton",
            "mouseWheel" to "ControlInputValue::MouseWheel",
            "controllerButton" to "SDL_GameControllerButton",
            "controllerAxis" to "SDL_GameControllerAxis",
            "controllerStick" to "ControllerStick",
        )
            .forEach { (member, name) ->
                check(types.namedMember("ControlInputValue", member, name, 4uL) == code) {
                    "Binding union layout changed"
                }
            }
    }

    fun write(target: ControlLayout) {
        target.first = first
        target.last = last
        slots.forEachIndexed { index, offset -> target.slots[index] = offset }
        target.key = key
        target.value = value
        target.linked = linked
        target.custom = custom
        target.gui = gui
        target.usage = usage
        target.type = type
        target.code = code
        target.modifiers = modifiers
        target.enabled = enabled
        target.spectating = spectating
        target.cutscene = cutscene
        target.stringSize = stringSize.toUInt()
        target.mouseValue = mouseValue
    }

    fun read(source: FmControl): ControlSnapshot {
        val mouseNames = listOf("left", "right", "middle", "button_4", "button_5")
        val mouseCodes =
            mouseNames
                .mapIndexed { index, name -> source.mouseCodes[index].toInt() to name }
                .toMap()

        fun bindings(effective: Boolean) =
            (0..3).map { index ->
                val binding = if (effective) source.effective[index] else source.bindings[index]
                val type = bindingTypes[binding.type.toInt()] ?: "Unknown(${binding.type})"
                val code = binding.code.toInt()
                val flags = binding.modifiers.toInt()
                BindingSnapshot(
                    listOf(
                        "keyboard_mouse_primary",
                        "keyboard_mouse_secondary",
                        "controller_primary",
                        "controller_secondary",
                    )[index],
                    type,
                    when (type) {
                        "Keyboard" -> keys[code]
                        "MouseButton" -> mouseCodes[code]
                        "MouseWheel" -> wheels[code]
                        "ControllerButton" -> controllerButtons[code]
                        "ControllerAxis" -> controllerAxes[code]
                        "ControllerStick" -> controllerSticks[code]
                        else -> null
                    },
                    code,
                    binding.modifierExpression
                        .toKString()
                        .split('+')
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() },
                    flags,
                )
            }
        return ControlSnapshot(
            source.id.toKString(),
            source.label.toKString(),
            source.description.toKString(),
            source.linked.toKString().takeIf { it.isNotEmpty() },
            source.bindingOwner.toKString(),
            source.custom != 0,
            if (source.custom != 0) source.enabled != 0 else null,
            if (source.custom != 0) source.spectating != 0 else null,
            if (source.custom != 0) source.cutscene != 0 else null,
            source.gui != 0,
            usages[source.usage] ?: "Unknown(${source.usage})",
            bindings(false),
            bindings(true),
            source.truncated != 0,
        )
    }
}
