@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.*
import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Explicit offline installed-game analysis; excluded from normal Gradle testing. */
class WorldMetadataAcceptanceTest {
    @Test
    fun resolvesEventSenderInputStateForwarding() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = EventSenderMetadata.resolve(image)
            println("Event sender source dispatch: ${metadata.sourceDispatch}")
            println("Event sender GUI dispatch: ${metadata.guiDispatch}")
            println("Event sender keyboard state ordering: ${metadata.orders}")
            val pointers = mutableMapOf<Long, Long>()
            for (type in listOf("17PlayerInputSource", "N4agui3GuiE", "16InputHandlerAgui")) {
                val table = image.symbol("_ZTV$type")
                ElfPointers(image).words(table.address, (table.size / 8).toInt()).drop(1)
                    .forEachIndexed { index, word ->
                        pointers[table.address + (index + 1) * 8L] = word.pointer()
                    }
                val name = image.symbol("_ZTI$type").address + 8
                pointers[name] = ElfPointers(image).words(name, 1).single().pointer()
            }
            for (bias in listOf(0L, 0x100000000L)) {
                val evidence = mutableListOf<Pair<Long, Int>>()
                fun loaded(address: Long, size: Int): ByteArray {
                    val result = image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
                    for ((slot, target) in pointers) for (byte in 0..7) {
                        val index = slot + bias + byte - address
                        if (index in 0 until size.toLong())
                            result[index.toInt()] = ((if (target == 0L) 0L else target + bias) ushr (byte * 8)).toByte()
                    }
                    return result
                }
                metadata.verify(image, bias) { address, size ->
                    evidence += address to size
                    loaded(address, size)
                }
                check(evidence.isNotEmpty())
                // Corrupt each observed region separately, including scalar headers and relocated pointer words.
                for (selected in evidence.distinct()) assertFails {
                    metadata.verify(image, bias) { address, size ->
                        loaded(address, size).also {
                            if (address to size == selected) it[0] = (it[0].toInt() xor 1).toByte()
                        }
                    }
                }
                println("Verified sender evidence at bias $bias: ${evidence.size} ranges; each corruption rejected")
            }
            assertFails { metadata.verify(image, -1) { _, _ -> error("Unexpected read") } }
            memScoped {
                val output = alloc<FmLinuxEventReceiverLayout>()
                val bias = 0x100000000L
                metadata.writeTo(output, bias)
                assertEquals((metadata.state.global + bias).toULong(), output.global)
                assertEquals(metadata.state.member.toUInt(), output.stateMember)
                assertEquals((metadata.guiInstance + bias).toULong(), output.guiInstance)
                assertEquals(metadata.guiDispatch.handlerMember.toUInt(), output.guiHandler)
                assertEquals((metadata.guiType.addressPoint + bias).toULong(), output.guiVtable)
                assertEquals((metadata.handlerType.typeInfo + bias).toULong(), output.handlerTypeInfo)
                assertFails { metadata.writeTo(output, -1) }
            }
        }
    }

    @Test
    fun resolvesNativePumpScalarCall() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val pump = image.symbol("_ZN8MainLoop13processEventsEb")
            val caller = image.symbol("_ZN8MainLoop10prePrepareEv")
            val proof = ScalarPumpCall.resolve(image, caller, pump)
            assertEquals(0, proof.value)
            println("Native pump boolean input and integer status call: $proof")
            val bytes = image.functionBytes(pump, 32768)
            val capture = EntryScalarSpill.inspect(
                bytes.slice(0, minOf(bytes.size, 512)),
                X64Instructions.Register(7, 4)
            )
            println("Native pump original scalar argument capture: $capture")
        }
    }

    @Test
    fun composesKeyboardEventConfigurationAndChecksLoadedEvidence() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = KeyboardEventMetadata.resolve(image)
            println("Native keyboard construction-copy reads: ${metadata.constructionCopies.mapValues { it.value.reads }}")
            println("Native keyboard update reads: ${metadata.stateUpdates.mapValues { it.value.reads }}")
            println("Native keyboard post-update reads: ${metadata.postUpdates.mapValues { it.value.reads }}")
            val bias = 0x100000000L
            var reads = 0
            metadata.verify(image, bias) { address, size ->
                ++reads
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(reads > 0)
            assertFails {
                metadata.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            memScoped {
                val output = alloc<FmLinuxKeyboardEventLayout>()
                metadata.writeTo(output)
                val clock = alloc<FmLinuxEventClock>()
                metadata.writeTo(clock, bias)
                assertEquals((metadata.clock.ticks.address + bias).toULong(), clock.ticks)
                assertEquals(metadata.clock.divisor, clock.divisor)
                assertEquals(metadata.header.extent.toUInt(), output.extent)
                assertEquals(metadata.update.code.toUInt(), output.code)
                for (offset in 0 until 256) {
                    assertEquals(
                        if (offset.toLong() in metadata.payload.defaults) 1.toUByte() else 0.toUByte(),
                        output.initialized[offset]
                    )
                    assertEquals((metadata.payload.defaults[offset.toLong()] ?: 0).toUByte(), output.defaults[offset])
                }
            }
            println("Verified keyboard event configuration: ${metadata.header}; $reads function/data ranges")
        }
    }

    @Test
    fun resolvesNativeEventPollCall() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val header = EventHeader.resolve(image)
            val poll = EventPollCall.resolve(image, header)
            println("Native event poll call: $poll")
            val size = SysVObjectSize.resolveDeleter(image, "_ZN13SimpleDeleterI10InputStateEclEPS0_")
            val update = InputStateKeyUpdate.resolve(image, size, header)
            val copies = PollEventCopies.resolve(image, poll, header, setOf(update.press.kind, update.release.kind))
            for (proof in copies.values) check((update.code until update.code + 4).all { it in proof.bytes })
            println("Native keyboard poll result copies: $copies")
            val payload = KeyboardEventPayload.resolve(image, header, update)
            val initialized = payload.defaults.keys + (update.code until update.code + 4) +
                    (header.type until header.type + 4) + (header.time until header.time + 8)
            check(copies.values.all { initialized.containsAll(it.bytes) })
            println("Native nonrepeat keyboard payload defaults: $payload")
        }
    }

    @Test
    fun composesTextEditingConfigurationAndChecksLoadedEvidence() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = UiTextMetadata.resolve(image)
            val bias = 0x100000000L
            var reads = 0
            metadata.verify(image, bias) { address, size ->
                ++reads
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(reads > 0)
            assertFails {
                metadata.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            memScoped {
                val output = alloc<FmLinuxUiTextConfig>()
                metadata.writeTo(output, bias)
                assertEquals(metadata.core.fields.key.toUInt(), output.event.key)
                assertEquals(metadata.core.backspace.key.toUInt(), output.event.backspace)
                assertEquals((metadata.core.focus + bias).toULong(), output.focus)
            }
            println("Verified text editing configuration: ${metadata.core.fields}; $reads function/data ranges")
        }
    }

    @Test
    fun resolvesTextEntryAbi() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
            println("Widget focus entry: ${WidgetFocusEntry.verify(image, widgetSize)}")
            val textBoxSize = SysVObjectSize.resolve(image, "4agui7TextBox")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui7TextBoxE")
            val handler = table.method(image, "_ZN4agui7TextBox14handleKeyboardERKNS_8KeyEventE")
            WidgetEventDispatch.verify(
                image,
                image.symbol("_ZN4agui7TextBox7keyDownERKNS_8KeyEventE"),
                textBoxSize,
                handler
            )
            println("TextBox keyDown arguments verified against its typed handleKeyboard slot")
            println(
                "Native key conversion: ${
                    listOf(
                        0,
                        8,
                        9,
                        13,
                        97
                    ).associateWith { KeyInputConversion.nativeKey(image, it) }
                }"
            )
            val fields = KeyEventFields.resolve(image)
            println("Key event construction and getter fields: $fields")
            println("Native key event defaults: ${fields.defaults(image)}")
            val inputFields = KeyInputFields.resolve(image, fields, InputClockBinding.resolve(image).member)
            println("Keyboard input field correspondence: $inputFields")
            println("Produced native keyboard values: ${KeyInputConversion.resolve(image, inputFields, setOf(8, 97))}")
        }
    }

    @Test
    fun composesKeyboardStateConfigurationAndChecksLoadedEvidence() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = KeyboardStateMetadata.resolve(image, MouseStateMetadata.resolve(image))
            val bias = 0x100000000L
            var reads = 0
            metadata.verify(image, bias) { address, size ->
                ++reads
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(reads > 0)
            assertFails {
                metadata.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            memScoped {
                val output = alloc<FmLinuxKeyStateLayout>()
                metadata.writeTo(output)
                assertEquals(metadata.map.stride.toUInt(), output.stride)
                assertEquals(metadata.update.held.toUInt(), output.held)
                assertEquals(metadata.modifiers.getValue("control").clear.single().toUInt(), output.clear)
            }
            println("Verified keyboard configuration: ${metadata.map}; $reads function/data ranges")
        }
    }

    @Test
    fun resolvesKeyboardStateUpdateCases() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val header = EventHeader.resolve(image)
            val state = InputStateLayout.resolve(image, MouseInputLayout.resolve(image))
            val resolved = InputStateKeyUpdate.resolve(image, state.size, header)
            println("Keyboard state update argument/store evidence: $resolved")
            val post = KeyPostUpdate.resolve(image, header, resolved)
            println("Keyboard post-update paths: $post")
            println("Native modifier keys: ${ModifierKey.resolve(image, resolved, post)}")
            println("Native key map: ${KeyMapLayout.resolve(image, state.size, resolved)}")
            println(
                "Keyboard event field uses: ${
                    InputEventUses.resolve(
                        image, header, resolved.code,
                        setOf(resolved.press.kind, resolved.release.kind)
                    )
                }"
            )
        }
    }

    @Test
    fun composesMouseGestureConfigurationAndChecksLoadedEvidence() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = MouseGestureMetadata.resolve(image)
            val bias = 0x100000000L
            var reads = 0
            metadata.verify(image, bias) { address, size ->
                ++reads
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(reads > 0)
            assertFails {
                metadata.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            memScoped {
                val config = alloc<FmLinuxMouseGestureConfig>()
                metadata.writeTo(config, bias)
                assertEquals((metadata.core.entries[0] + bias).toULong(), config.enter)
                assertEquals((metadata.core.entries[4] + bias).toULong(), config.leave)
                assertEquals(metadata.core.event.extent.toUInt(), config.event.size)
                assertEquals(1U, config.event.hasPrevious)
                assertEquals(metadata.enter.template.previous?.toUInt(), config.event.previous)
                val buttons = listOf(
                    SdlButtonAdmission.Button.LEFT, SdlButtonAdmission.Button.RIGHT,
                    SdlButtonAdmission.Button.MIDDLE
                )
                buttons.forEachIndexed { index, button ->
                    assertEquals(metadata.core.buttons.values.getValue(button).toUShort(), config.buttons[index])
                }
                assertFails { metadata.writeTo(config, Long.MAX_VALUE) }
            }
            println("Composed mouse gesture: ${metadata.core.buttons.values}, $reads evidence ranges")
        }
    }

    @Test
    fun resolvesMouseButtonMasksForWidgetEvents() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val input = MouseInputLayout.resolve(image)
            val conversion = SdlButtonConversion.resolve(image)
            val event = MouseEventFields.resolve(image)
            println("Widget mouse button masks: ${MouseButtonMasks.resolve(image, input, conversion, event)}")
        }
    }

    @Test
    fun resolvesNativeModalPredicate() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val modal = UiModalMetadata.resolve(image)
            val bias = 0x100000000L
            var reads = 0
            modal.verify(image, bias) { address, size ->
                ++reads
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(reads > 0)
            assertFails {
                modal.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            println("Native modal predicate: $modal")
        }
    }

    @Test
    fun resolvesFiniteCaptureMetadata() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val capture = UiCaptureMetadata.resolve(image)
            val bias = 0x100000000L
            var reads = 0
            capture.verify(image, bias) { address, size ->
                ++reads
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(reads > 0)
            assertFails {
                capture.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            println("Finite capture metadata: $capture")
        }
    }

    @Test
    fun derivesTheNestedMouseDownClickFlag() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            println("Nested mouse-down click flag: ${WidgetClickGate.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun resolvesMouseStateAdapterMetadataAndPostUpdatePaths() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val metadata = MouseStateMetadata.resolve(image)
            println("Mouse state post-update paths: ${metadata.postPaths.mapValues { it.value.size }}")
            println("Mouse event scalar copy bytes: ${metadata.copyCases.mapValues { it.value.bytes.sorted() }}")
            println("Mouse update selected argument uses: ${metadata.updateUses}")
            println("Mouse state adapter: state=${metadata.state}, held=${metadata.update.member}")
            var ranges = 0
            metadata.verify(image, 0x1000) { address, size ->
                ++ranges
                image.virtualBytes(address - 0x1000, size.toLong()).bytes(0, size)
            }
            val table = metadata.conversion.admission.table.address
            assertFails {
                metadata.verify(image, 0) { address, size ->
                    image.virtualBytes(address, size.toLong()).bytes(0, size).also {
                        if (table >= address && table - address < size) {
                            val offset = (table - address).toInt()
                            it[offset] = (it[offset].toInt() xor 1).toByte()
                        }
                    }
                }
            }
            println("Mouse state code/data evidence: $ranges ranges; modified native button table rejected")
        }
    }

    @Test
    fun derivesTheInputStateObjectAndDecodesItsUpdate() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val state = InputStateLayout.resolve(image, MouseInputLayout.resolve(image))
            println("Input state object: $state")
            val update = image.symbol("_ZN10InputState6updateERK5Event")
            val flow = X64ControlFlow.resolve(image, update)
            println("Input state update: ${flow.instructions.size} instructions, ${flow.reachable.size} reachable")
            println(
                "Input state mouse cases: ${
                    InputStateMouseUpdate.resolve(
                        image,
                        state,
                        SdlButtonConversion.resolve(image)
                    )
                }"
            )
        }
    }

    @Test
    fun derivesTheSdlMouseButtonPayload() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val conversion = SdlButtonConversion.resolve(image)
            println("Native mouse payload: code=${conversion.payload.code}, store=${conversion.payload.store}, kinds=${conversion.kinds}")
            val mask = InputMaskExpression.resolve(image, MouseInputLayout.resolve(image))
            assertEquals(mask.code, conversion.payload.code)
            println("Native input-mask field matches the SDK-derived payload")
        }
    }

    @Test
    fun decodesTheSdlEventConversionFlow() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("_ZN5Event15convertSDLEventERKNS_5StateERK9SDL_EventR12CompactDequeIS_Ef")
            val flow = X64ControlFlow.resolve(image, function)
            println("SDL event conversion: ${flow.instructions.size} decoded instructions, ${flow.reachable.size} reachable")
            for (table in GuardedByteTable.resolve(image, function)) {
                println(
                    "SDL byte table: input=${table.input}, address=${table.address}, values=${table.values}, " +
                            "accepted=${table.indices.withIndex().filter { it.value != null }.map { it.index }}"
                )
                val admission = SdlButtonAdmission.analyze(flow, table)
                println(
                    "SDK button admission: ${
                        admission.cases.map {
                            Triple(
                                it.button,
                                it.transition,
                                it.path.size
                            )
                        }
                    }"
                )
            }
        }
    }

    @Test
    fun derivesMouseInputWheelField() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val event = MouseEventFields.resolve(image)
            val input = MouseInputLayout.resolve(image)
            println("Mouse input fields: ${MouseInputConversion.resolve(image, input, event)}")
        }
    }

    @Test
    fun derivesTheNativeMouseInputMaskExpression() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            println("Mouse input mask: ${InputMaskExpression.resolve(image, MouseInputLayout.resolve(image))}")
        }
    }

    @Test
    fun crossChecksTheMouseEventFields() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file -> println("Mouse event fields: ${MouseEventFields.resolve(ElfImage(file.view))}") }
    }

    @Test
    fun resolvesNativeEnterConstruction() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val fields = MouseEventFields.resolve(image)
            val enter = MouseEnterConstruction.resolve(image, fields)
            val bias = 0x100000000L
            var reads = 0
            enter.verify(image, bias) { address, size ->
                ++reads
                image.virtualBytes(address - bias, size.toLong()).bytes(0, size)
            }
            check(reads > 0)
            assertFails {
                enter.verify(image, bias) { address, size ->
                    image.virtualBytes(address - bias, size.toLong()).bytes(0, size).also {
                        it[0] = (it[0].toInt() xor 1).toByte()
                    }
                }
            }
            println("Native enter construction: ${enter.template}, GUI member=${enter.guiPrevious}, base=${enter.widgetTargetable}, checked ranges=$reads")
        }
    }

    @Test
    fun verifiesNamedMouseEventConstruction() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val fields = MouseEventFields.resolve(image)
            println("Named mouse construction: ${MouseEventConstruction.resolve(image, fields)}")
        }
    }

    @Test
    fun derivesTheInlineDequeuedMouseInputFrame() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val input = MouseInputLayout.resolve(image)
            val function = image.symbol("_ZN4agui3Gui5logicEb")
            val flow = X64ControlFlow.resolve(image, function)
            val inlines = DwarfInlines(image).find(function, "logic", setOf("dequeueMouseInput"))
            for (inline in inlines) {
                val ranges =
                    inline.ranges.map { DwarfRanges.Range(it.start - function.address, it.end - function.address) }
                val local = InlineFrameCopy.resolve(flow, ranges, input.queue.extent)
                println("Typed MouseInput local copy: $local")
                val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
                val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
                val parent = SysVRectangleAbi.resolve(
                    image,
                    image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                    widgetSize,
                    table
                ).parent
                val event = MouseEventCopy.resolve(
                    image, image.symbol("_ZNK4agui10MouseEvent17copyWithNewSourceEPNS_6WidgetE"),
                    widgetSize, parent, table.method(image, "_ZNK4agui6Widget14getLeftPaddingEv").slot,
                    table.method(image, "_ZNK4agui6Widget13getTopPaddingEv").slot
                )
                val target = image.symbol("_ZN4agui3Gui15handleMouseAxesENS_10MouseEventE")
                val calls = flow.instructions.filter {
                    it.operation == X64Instructions.Operation.CALL &&
                            (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == target.address
                }
                check(calls.isNotEmpty())
                for (call in calls) println(
                    "MouseInput to MouseEvent at ${call.offset}: " + FrameFieldCopies.resolve(
                        flow,
                        call.offset, local.frame, input.queue.extent, event.extent, mapOf(
                            "alt" to InlineArgumentFields.Field(input.alt.field, 1),
                            "control" to InlineArgumentFields.Field(input.control, 1),
                            "shift" to InlineArgumentFields.Field(input.shift, 1),
                            "time" to InlineArgumentFields.Field(input.time, 8)
                        ), argument = 4
                    )
                )
            }
        }
    }

    @Test
    fun derivesMouseFieldsFromNamedInlineGetterRanges() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val debug = DwarfInlines(image)
            // The independent event-copy proof supplies bounds; this test checks named field/read association.
            val size = SysVObjectSize.resolve(image, "4agui6Widget")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            val parent = SysVRectangleAbi.resolve(
                image,
                image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                size,
                table
            ).parent
            val copy = MouseEventCopy.resolve(
                image, image.symbol("_ZNK4agui10MouseEvent17copyWithNewSourceEPNS_6WidgetE"),
                size, parent, table.method(image, "_ZNK4agui6Widget14getLeftPaddingEv").slot,
                table.method(image, "_ZNK4agui6Widget13getTopPaddingEv").slot
            )
            val selected = listOf(
                Triple("_ZN4agui6Widget17dispatchMouseDownERKNS_10MouseEventE", "dispatchMouseDown", "getButton" to 2),
                Triple(
                    "_ZN7ModsGui21onDialogButtonPressedERKN4agui10MouseEventE",
                    "onDialogButtonPressed",
                    "control" to 1
                ),
                Triple(
                    "_ZNSt17_Function_handlerIFvRKN4agui10MouseEventEEZN15MapGeneratorGui24prepareSubheaderElementsEvE3${'$'}_2E9_M_invokeERKSt9_Any_dataS3_",
                    "_M_invoke", "shift" to 1
                ),
            )
            val anchors = selected.flatMap { (symbol, owner, getter) ->
                InlineArgumentFields.resolveEvidence(
                    image, image.symbol(symbol), owner, 6, copy.extent.toLong(),
                    mapOf(getter), debug
                ).also { println("$owner inline field: $it") }.entries.map { it.toPair() }
            }.toMap()
            val stackAnchors = anchors.filterKeys { it in setOf("getButton", "shift") }
            // Alt's range contains a coalesced two-byte load, not independently identified Boolean storage.
            val widths = stackAnchors.mapValues { it.value.field.width } + mapOf(
                "alt" to 2,
                "getTimeStamp" to 8,
                "getEvent" to 4
            )
            println(
                "Incoming stack mouse fields: " + InlineArgumentFields.resolveStack(
                    image,
                    image.symbol("_ZN4agui3Gui15handleMouseAxesENS_10MouseEventE"),
                    "handleMouseAxes",
                    copy.extent.toLong(),
                    stackAnchors,
                    widths,
                    debug
                )
            )
        }
    }

    @Test
    fun connectsHandlerModifierBytesToTheBoundedMouseInputFields() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val output = MouseInputLayout.resolve(image)
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            val target = image.symbol("_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_")
            val sink = flow.instructions.single {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == target.address
            }.offset
            println(
                "Original handler modifier sources: " + InputModifierSources.resolve(
                    flow, sink,
                    SysVObjectSize.resolve(image, "16InputHandlerAgui"), output.queue.extent,
                    InputModifierSources(output.alt.field, output.control, output.shift)
                )
            )
        }
    }

    @Test
    fun comparesTheInlineAltDecisionAndFindsItsLocalCopy() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            val target = image.symbol("_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_")
            val sink = flow.instructions.single {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == target.address
            }.offset
            println(
                "Inline Alt decision/copy, conditional on lookup ABI and receiver identity: " +
                        InlineModifier.resolve(image, function, flow, sink)
            )
        }
    }

    @Test
    fun assemblesTheBoundedMouseInputLayout() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Bounded mouse input layout: ${MouseInputLayout.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun derivesTheMouseInputQueueElementExtent() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val target = image.symbol("_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_")
            val flow = X64ControlFlow.resolve(image, function)
            val sink = flow.instructions.single {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == target.address
            }.offset
            println(
                "MouseInput queue: ${
                    LocalQueueAppend(flow).at(
                        sink,
                        SysVObjectSize.resolve(image, "16InputHandlerAgui")
                    )
                }"
            )
        }
    }

    @Test
    fun tracesTheEventTimestampIntoTheMouseInputArgument() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val header = EventHeader.resolve(image)
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            val target = image.symbol("_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_")
            val sink = flow.instructions.single {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == target.address
            }.offset
            val arguments = SysVArgumentFlow(flow)
            val copies = LocalFieldCopies(flow)
            val fields = flow.instructions.filter {
                it.operation == X64Instructions.Operation.SCALAR_MOV &&
                        arguments.source(it.offset) == SysVArgumentFlow.Read(
                    SysVArgumentFlow.Reference(6, header.time),
                    8
                )
            }.flatMap { copies.fromRead(it.offset, sink) }.distinct()
            check(fields.size == 1)
            println("MouseInput argument from Event time: $fields (pointee extent still requires independent proof)")
        }
    }

    @Test
    fun tracesModifierReturnsIntoTheMouseInputArgument() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            fun call(name: String): Long {
                val address = image.symbol(name).address
                return flow.instructions.single {
                    it.operation == X64Instructions.Operation.CALL &&
                            (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == address
                }.offset
            }

            val sink = call("_ZNSt5dequeIN4agui10MouseInputESaIS1_EE16_M_push_back_auxIJRKS1_EEEvDpOT_")
            for (name in listOf("_ZNK10InputState11isShiftDownEv", "_ZNK10InputState10isCtrlDownEv")) {
                val fields = LocalFieldCopies(flow).fromReturn(call(name), sink)
                check(fields.size == 1)
                println("MouseInput argument from $name: $fields (pointee extent still requires independent proof)")
            }
        }
    }

    @Test
    fun derivesTheNativeEventHeaderFromTypedConstruction() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Native event header: ${EventHeader.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun resolvesTheGuiInputClockBinding() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("GUI input clock association: ${InputClockBinding.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun verifiesTheConcreteInputClockAbi() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Concrete input clock ABI: ${InputClock.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun derivesNativeMouseEventConstructionWrites() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVObjectSize.resolve(image, "4agui6Widget")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            val parent = SysVRectangleAbi.resolve(
                image,
                image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                size,
                table
            ).parent
            val event = MouseEventCopy.resolve(
                image, image.symbol("_ZNK4agui10MouseEvent17copyWithNewSourceEPNS_6WidgetE"),
                size, parent, table.method(image, "_ZNK4agui6Widget14getLeftPaddingEv").slot,
                table.method(image, "_ZNK4agui6Widget13getTopPaddingEv").slot
            )
            val function = image.symbol("_ZN4agui3Gui5logicEb")
            val aggregate = LocalAggregate.resolve(image, function)
            val instructions = X64Instructions(image.functionBytes(function, 32768)).all(8192)
            for (entry in listOf(
                "17dispatchMouseDown",
                "15dispatchMouseUp",
                "18dispatchMouseEnter",
                "18dispatchMouseLeave"
            )) {
                val callee = image.symbol("_ZN4agui6Widget${entry}ERKNS_10MouseEventE")
                val calls = instructions.filter {
                    it.operation == X64Instructions.Operation.CALL &&
                            (it.destination as? X64Instructions.Immediate)?.value?.plus(function.address) == callee.address
                }
                check(calls.isNotEmpty())
                for (call in calls) {
                    val proof = aggregate.at(call.offset, event.extent)
                    check(event.source in proof.receiverFields)
                    check(proof.constants.isNotEmpty())
                    println("Native $entry construction: $proof")
                }
            }
        }
    }

    @Test
    fun resolvesTheNativeInputConversionSwitches() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val tables = X64JumpTables.resolve(image, function)
            check(tables.isNotEmpty())
            println("Bounded input conversion switches: ${tables.map { it.targets.size }}")
        }
    }

    @Test
    fun derivesTheSharedMouseEventMaskGate() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVObjectSize.resolve(image, "4agui6Widget")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            val parent = SysVRectangleAbi.resolve(
                image,
                image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                size,
                table
            ).parent
            val event = MouseEventCopy.resolve(
                image, image.symbol("_ZNK4agui10MouseEvent17copyWithNewSourceEPNS_6WidgetE"),
                size, parent, table.method(image, "_ZNK4agui6Widget14getLeftPaddingEv").slot,
                table.method(image, "_ZNK4agui6Widget13getTopPaddingEv").slot
            )

            fun gate(entry: String, method: String) = WidgetEventDispatch.mask(
                image,
                image.symbol("_ZN4agui6Widget${entry}ERKNS_10MouseEventE"), size, event.extent.toLong(),
                table.method(image, "_ZN4agui6Widget${method}ERKNS_10MouseEventE")
            )

            val down = gate("17dispatchMouseDown", "9mouseDown")
            assertEquals(down, gate("13dispatchClick", "10mouseClick"))
            println("Shared mouse event bitset gate: $down")
        }
    }

    @Test
    fun verifiesWidgetMouseDispatchArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVObjectSize.resolve(image, "4agui6Widget")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            for ((entry, method) in listOf(
                "18dispatchMouseEnter" to "10mouseEnter",
                "17dispatchMouseDown" to "9mouseDown",
                "13dispatchClick" to "10mouseClick",
                "15dispatchMouseUp" to "7mouseUp",
                "18dispatchMouseLeave" to "10mouseLeave"
            )) {
                WidgetEventDispatch.verify(
                    image, image.symbol("_ZN4agui6Widget${entry}ERKNS_10MouseEventE"), size,
                    table.method(image, "_ZN4agui6Widget${method}ERKNS_10MouseEventE")
                )
                println("Verified Widget event entry: $entry")
            }
        }
    }

    @Test
    fun derivesMouseEventCopyArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVObjectSize.resolve(image, "4agui6Widget")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            val parent = SysVRectangleAbi.resolve(
                image,
                image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                size,
                table
            ).parent
            println(
                "Mouse event copy: ${
                    MouseEventCopy.resolve(
                        image, image.symbol("_ZNK4agui10MouseEvent17copyWithNewSourceEPNS_6WidgetE"),
                        size, parent, table.method(image, "_ZNK4agui6Widget14getLeftPaddingEv").slot,
                        table.method(image, "_ZNK4agui6Widget13getTopPaddingEv").slot
                    )
                }"
            )
        }
    }

    @Test
    fun derivesGuiTargetStateReset() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val guiSize = SysVObjectSize.resolve(image, "4agui3Gui")
            val gate = SysVPointerGate.resolve(image, "_ZN4agui3Gui11handleHoverEv", guiSize)
            val release =
                SysVTargeterRelease.resolve(image, "_ZN4agui19GenericTargeterBase8attachToEPNS_17GenericTargetableE")
            val base = ItaniumClass.resolve(image, "N4agui6WidgetE").directBase(
                ItaniumClass.resolve(image, "N4agui17GenericTargetableE"),
                SysVObjectSize.resolve(image, "4agui6Widget"),
                release.targetableExtent
            )
            println(
                "GUI target state reset: ${
                    GuiTargetReset.resolve(
                        image, "_ZN4agui3Gui23dispatchWidgetDestroyedEPNS_6WidgetE",
                        guiSize, gate.member, base, release
                    )
                }"
            )
        }
    }

    @Test
    fun derivesGuiHoverEntryGuard() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            println(
                "GUI hover entry guard: ${
                    SysVPointerGate.resolve(
                        image, "_ZN4agui3Gui11handleHoverEv",
                        SysVObjectSize.resolve(image, "4agui3Gui")
                    )
                }"
            )
        }
    }

    @Test
    fun derivesNativeTargeterRelease() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println(
                "Native targeter release: ${
                    SysVTargeterRelease.resolve(
                        ElfImage(file.view),
                        "_ZN4agui19GenericTargeterBase8attachToEPNS_17GenericTargetableE"
                    )
                }"
            )
        }
    }

    @Test
    fun derivesWidgetTargetableBase() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val widget = ItaniumClass.resolve(image, "N4agui6WidgetE")
            val base = ItaniumClass.resolve(image, "N4agui17GenericTargetableE")
            val targeter = SysVTargeterRelease.resolve(
                image,
                "_ZN4agui19GenericTargeterBase8attachToEPNS_17GenericTargetableE"
            )
            // Capture ownership and live links still require validation at the GUI safe point.
            println(
                "Widget targetable base: ${
                    widget.directBase(
                        base, SysVObjectSize.resolve(image, "4agui6Widget"),
                        targeter.targetableExtent
                    )
                }"
            )
        }
    }

    @Test
    fun derivesWidgetVisibilityFlagMutations() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVObjectSize.resolve(image, "4agui6Widget")
            val visible = SysVFlagMutation.resolveBoolean(image, "_ZN4agui6Widget10setVisibleEb", size)
            val hidden = SysVFlagMutation.resolveConstant(image, "_ZN4agui6Widget12hideBySearchEv", size, true)
            assertEquals(
                hidden,
                SysVFlagMutation.resolveConstant(image, "_ZN4agui6Widget12showBySearchEv", size, false)
            )
            println("Native own-widget visibility=$visible, search-hidden=$hidden")
        }
    }

    @Test
    fun assemblesWorldQueryMetadata() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            WorldQueryMetadata.resolve(ElfImage(file.view))
            println("World-query layout, player selection and Lua API metadata resolved")
        }
    }

    @Test
    fun assemblesPlayerLayout() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val gameSize = SysVOwnedObjectSize.resolve(
                image,
                "_ZNSt10unique_ptrI4GameSt14default_deleteIS0_EED2Ev", "_ZN4GameD2Ev"
            ).size
            println("Combined player layout: ${PlayerLayout.resolve(image, gameSize, LuaStateLayout.resolve(image))}")
        }
    }

    @Test
    fun verifiesLocalPlayerSelection() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val gameSize = SysVOwnedObjectSize.resolve(
                image,
                "_ZNSt10unique_ptrI4GameSt14default_deleteIS0_EED2Ev", "_ZN4GameD2Ev"
            ).size
            val playerSize = SysVLineAllocation.resolve(
                image,
                "_ZN3Map8loadDataER15MapDeserialiserRK17GlobalModSettingsP16ProgressObserver",
                "_ZN6PlayerC2ER3MapR15MapDeserialiser"
            )
            val direct = SysVArgumentMember.resolve(image, "_ZN4Game15connectToPlayerEP6Playerb", gameSize)
            val view = SysVMemberCalls.directPrefix(image, "_ZN4GameD2Ev", "_ZN8GameView9unloadGuiEv", gameSize)
            val indirect = SysVArgumentMember.resolve(
                image,
                "_ZN8GameViewC2ER4GameP6Player9NamedBoolI15IsSImulationTagEP18EngineFramebuffersS4_I17MuteWindSoundsTagE",
                SysVObjectSize.resolve(image, "8GameView"), 2
            )
            LocalPlayerSelection.verify(image, playerSize, direct, view, indirect)
            println("Native local-player selection verified: Game player=$direct, GameView=$view, view player=$indirect")
        }
    }

    @Test
    fun derivesPlayerLuaIndex() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val size = SysVLineAllocation.resolve(
                image,
                "_ZN3Map8loadDataER15MapDeserialiserRK17GlobalModSettingsP16ProgressObserver",
                "_ZN6PlayerC2ER3MapR15MapDeserialiser"
            )
            println("Player Lua index: ${PlayerIndex.resolve(image, size, LuaStateLayout.resolve(image))}")
        }
    }

    @Test
    fun derivesPlayerAllocationBound() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            println(
                "Player allocation extent: ${
                    SysVLineAllocation.resolve(
                        image,
                        "_ZN3Map8loadDataER15MapDeserialiserRK17GlobalModSettingsP16ProgressObserver",
                        "_ZN6PlayerC2ER3MapR15MapDeserialiser"
                    )
                }"
            )
        }
    }

    @Test
    fun derivesTypedPlayerPointerMembers() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val gameSize = SysVOwnedObjectSize.resolve(
                image,
                "_ZNSt10unique_ptrI4GameSt14default_deleteIS0_EED2Ev",
                "_ZN4GameD2Ev"
            ).size
            println(
                "Typed Game Player pointer: ${
                    SysVArgumentMember.resolve(
                        image,
                        "_ZN4Game15connectToPlayerEP6Playerb",
                        gameSize
                    )
                }"
            )
            println(
                "Typed LuaPlayer Player pointer: ${
                    SysVArgumentMember.resolve(
                        image,
                        "_ZN9LuaPlayerC2EP6PlayerP9lua_State",
                        SysVObjectSize.resolve(image, "9LuaPlayer")
                    )
                }"
            )
            println(
                "Typed GameView Player pointer: ${
                    SysVArgumentMember.resolve(
                        image,
                        "_ZN8GameViewC2ER4GameP6Player9NamedBoolI15IsSImulationTagEP18EngineFramebuffersS4_I17MuteWindSoundsTagE",
                        SysVObjectSize.resolve(image, "8GameView"), 2
                    )
                }"
            )
        }
    }

    @Test
    fun assemblesLuaQueryApi() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Verified query API state prerequisites: ${LuaApi.resolve(ElfImage(file.view)).state}")
        }
    }

    @Test
    fun assemblesLuaStateIdentityAndStackLayout() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Lua live-state validation layout: ${LuaStateLayout.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun derivesLuaEmbeddedBaseFrame() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val stack = SysVLuaStack.resolve(image)
            val allocation = SysVLuaAllocation.resolve(image)
            val loader = SysVLuaLoadFrame.resolve(image, stack, allocation.globalOffset)
            val call = SysVLuaProtectedCall.resolve(image, stack, loader, allocation.globalOffset)
            println("Lua embedded base frame: ${SysVLuaBaseFrame.resolve(image, allocation, stack, call)}")
        }
    }

    @Test
    fun verifiesLuaProtectedCallArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val stack = SysVLuaStack.resolve(image)
            val stateSize = SysVLuaAllocation.resolve(image).globalOffset
            val loader = SysVLuaLoadFrame.resolve(image, stack, stateSize)
            println(
                "Lua protected-call ABI and native admission fields: ${
                    SysVLuaProtectedCall.resolve(
                        image,
                        stack,
                        loader,
                        stateSize
                    )
                }"
            )
        }
    }

    @Test
    fun verifiesLuaLoaderStatusReturn() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            SysVLuaStatusReturn.verifyLoader(ElfImage(file.view))
            println("Lua loader preserves its protected-call int result across cleanup and normal returns")
        }
    }

    @Test
    fun verifiesLuaParserModeAndNameArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val layout = SysVLuaLoadFrame.resolve(
                image,
                SysVLuaStack.resolve(image),
                SysVLuaAllocation.resolve(image).globalOffset
            )
            SysVLuaParserArguments.verify(image, layout)
            println("Lua parser consumes the original name and checks binary/text mode arguments")
        }
    }

    @Test
    fun derivesLuaProtectedCallRecord() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Lua protected-call callback record: ${SysVLuaCallRecord.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun crossChecksLuaLoadReaderInitialization() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            println(
                "Lua load initialized reader: ${
                    SysVLuaLoadFrame.resolve(
                        image,
                        SysVLuaStack.resolve(image),
                        SysVLuaAllocation.resolve(image).globalOffset
                    )
                }"
            )
        }
    }

    @Test
    fun derivesLuaReaderCallbackArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Parser reader callback: ${SysVLuaReader.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun verifiesLuaStringResultArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            println("Lua string result ABI: ${SysVLuaStringRead.resolve(image, SysVLuaStack.resolve(image).valueSize)}")
        }
    }

    @Test
    fun verifiesLuaNumberPushArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            SysVLuaNumberPush.verify(image, SysVLuaStack.resolve(image), SysVLuaAllocation.resolve(image).globalOffset)
            println("Lua number push consumes the XMM0 double when stack capacity is sufficient")
        }
    }

    @Test
    fun verifiesLuaStringPushArguments() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            SysVLuaStringPush.verify(image, SysVLuaAllocation.resolve(image).globalOffset)
            println("Lua string push forwards state, bytes and size, including the collector path")
        }
    }

    @Test
    fun derivesLuaProtectionCallbackAbi() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val allocation = SysVLuaAllocation.resolve(image)
            println(
                "Lua protected callback and normal restoration: ${
                    SysVLuaProtection.resolve(
                        image,
                        allocation.globalOffset
                    )
                }"
            )
        }
    }

    @Test
    fun provesLuaZeroIndexStackReset() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val allocation = SysVLuaAllocation.resolve(image)
            val stack = SysVLuaStack.resolve(image)
            val capacity = SysVLuaCapacity.resolve(image, stack, allocation.globalOffset)
            SysVLuaStackReset.verify(image, stack, capacity, allocation.globalOffset)
            println("Lua settop(state, 0) verified for a well-formed frame")
        }
    }

    @Test
    fun derivesLuaAllocationIdentity() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val allocation = SysVLuaAllocation.resolve(image)
            println("Lua allocation requiring live identity and readiness validation: $allocation")
            val stack = SysVLuaStack.resolve(image)
            println("Lua stack capacity member: ${SysVLuaCapacity.resolve(image, stack, allocation.globalOffset)}")
        }
    }

    @Test
    fun derivesDefaultScriptSelection() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val script = LuaScriptLayout.resolve(image)
            println("Native default script selection: ${LuaContextLayout.resolve(image, script.size)}")
        }
    }

    @Test
    fun derivesScriptStateMember() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println(
                "Script state association requiring selection/readiness/ABI validation: ${
                    LuaScriptLayout.resolve(
                        ElfImage(file.view)
                    )
                }"
            )
        }
    }

    @Test
    fun assemblesWorldLayoutFromTheSelectedExecutable() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            println("Combined world layout requiring live context validation: ${WorldLayout.resolve(ElfImage(file.view))}")
        }
    }

    @Test
    fun derivesWorldObjectBoundsAndStackAccessFromTheSelectedExecutable() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val layout = SysVLuaStack.resolve(image)
            println("Lua stack instruction evidence: $layout")
            val global = SysVGlobalAllocation.resolve(
                image, "_ZN9MainTasks6createERK13ParsedOptions",
                "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_", "global"
            )
            println("Global context allocation evidence: $global")
            for (type in listOf("4Game", "8Scenario")) {
                val owner = SysVOwnedObjectSize.resolve(
                    image, "_ZNSt10unique_ptrI${type}St14default_deleteIS0_EED2Ev",
                    "_ZN${type}D2Ev"
                )
                println("Owned $type object evidence: $owner")
                if (type == "8Scenario") {
                    val member = SysVGlobalMember.resolve(image, "_ZN8ScenarioD2Ev", "global", global.size, owner.size)
                    println("Identity-guarded global scenario member: $member")
                }
            }
        }
    }

    @Test
    fun derivesScenarioOwnedMemberCalls() {
        val path =
            checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select the installed executable explicitly" }.toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val failure = SysVNoReturn(image, setOf("abort", "__cxa_throw", "_ZSt20__throw_system_errori"))
                .resolve("_Z19ReleaseAssertFailedPKcjS0_")
            println("Scenario assertion has verified no-return control flow")
            val owner = SysVOwnedObjectSize.resolve(
                image, "_ZNSt10unique_ptrI8ScenarioSt14default_deleteIS0_EED2Ev",
                "_ZN8ScenarioD2Ev"
            )
            val contextSize = SysVObjectSize.resolve(image, "10LuaContext")
            val contextDestructor = ItaniumVtable.resolve(image, "_ZTV10LuaContext")
                .method(image, "_ZN10LuaContextD0Ev")
            val destructor = image.symbol("_ZN8ScenarioD2Ev")
            val contextCandidates = SysVMemberCalls.virtual(
                image.functionBytes(destructor, 8192),
                destructor.address, contextDestructor.slot, owner.size
            )
            println("Virtual context member candidates requiring live RTTI validation: $contextCandidates; size=$contextSize")
            val game = SysVMemberCalls.direct(image, "_ZN8ScenarioD2Ev", "_ZN4GameD2Ev", owner.size, setOf(failure))
            println("Typed scenario game member: $game")
        }
    }
}
