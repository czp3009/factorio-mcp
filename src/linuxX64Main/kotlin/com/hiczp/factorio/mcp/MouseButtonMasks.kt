package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * SDL button identity, native Event code, queue mask and the actual Widget event field share this
 * proof.
 */
internal data class MouseButtonMasks(val values: Map<SdlButtonAdmission.Button, Int>) {
    companion object {
        fun resolve(
            image: ElfImage,
            input: MouseInputLayout,
            conversion: SdlButtonConversion,
            event: MouseEventFields,
        ): MouseButtonMasks {
            val masks = InputButtonMasks.resolve(image, input, conversion)
            val values =
                values(
                    masks,
                    SdlButtonAdmission.Button.entries.associateWith {
                        checkNotNull(conversion.admission.table.value(it.sdkValue)).toInt()
                    },
                )
            val function = image.symbol("_ZN4agui3Gui5logicEb")
            val flow = X64ControlFlow.resolve(image, function)
            val locals =
                image.inlines
                    .find(function, "logic", setOf("dequeueMouseInput"))
                    .map { inline ->
                        InlineFrameCopy.resolve(
                            flow,
                            inline.ranges.map {
                                DwarfRanges.Range(
                                    it.start - function.address,
                                    it.end - function.address,
                                )
                            },
                            input.queue.extent,
                        )
                    }
                    .distinct()
            val local = locals.singleOrNull() ?: error("Mouse mask has no unique dequeued object")
            val frame = SysVLocalArgument(flow)
            val source =
                flow.instructions.filter {
                    it.operation == Operation.MOVZX &&
                        (it.source as? Memory)?.let { memory ->
                            memory.width == 2 &&
                                frame.address(it.offset, memory) == local.frame + masks.field
                        } == true
                }
            val axes = image.symbol("_ZN4agui3Gui15handleMouseAxesENS_10MouseEventE")
            val sinks =
                flow.instructions.filter {
                    it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) ==
                            axes.address
                }
            require(sinks.isNotEmpty())
            for (sink in sinks) {
                val rejected = mutableListOf<String>()
                val candidates =
                    source.filter { start ->
                        try {
                            LocalMaskCopy.verify(
                                flow,
                                start.offset,
                                sink.offset,
                                values.values.toSet(),
                                event.button,
                                event.extent,
                                argument = 4,
                            )
                            true
                        } catch (failure: IllegalArgumentException) {
                            rejected += "${start.offset}: ${failure.message}"
                            false
                        } catch (failure: IllegalStateException) {
                            rejected += "${start.offset}: ${failure.message}"
                            false
                        }
                    }
                require(candidates.size == 1) {
                    "Widget event button has no unique unchanged native queue-mask copy at ${sink.offset}: $rejected"
                }
            }
            return MouseButtonMasks(values)
        }

        fun values(
            masks: InputButtonMasks,
            codes: Map<SdlButtonAdmission.Button, Int>,
        ): Map<SdlButtonAdmission.Button, Int> {
            require(codes.keys == SdlButtonAdmission.Button.entries.toSet())
            require(codes.values.all { it in 1..255 })
            val result =
                codes.mapValues { (_, code) ->
                    masks.values.getValue(code.toLong()).also {
                        require(it in 2..65535 && it and (it - 1) == 0) {
                            "Mouse button does not have a unique non-unit word mask"
                        }
                    }
                }
            require(result.values.distinct().size == result.size) { "Mouse button masks overlap" }
            return result
        }
    }
}
