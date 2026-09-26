package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.*

class UiSnapshotTest {
    @Test
    fun switchesPreservePositionInsteadOfInventingBooleanOrLabelMeaning() {
        val snapshot =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(
                    node(0)
                        .copy(
                            properties =
                                WidgetProperties(switch = WidgetSwitch(253, "unknown_253", true))
                        )
                ),
            )
        val value =
            snapshot
                .uiJson(false)
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("switch")
                .jsonObject
        assertEquals(253, value.getValue("state_value").jsonPrimitive.int)
        assertEquals("unknown_253", value.getValue("state").jsonPrimitive.content)
        assertTrue(value.getValue("allow_none").jsonPrimitive.boolean)
        assertEquals(3, value.size)
    }

    @Test
    fun qualityConditionsKeepRawIndicesAndUnknownComparisons() {
        val value =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(
                    node(0)
                        .copy(
                            properties =
                                WidgetProperties(
                                    qualityCondition =
                                        WidgetQualityCondition(0, 251, "unknown_251")
                                )
                        )
                ),
            )
                .uiJson(false)
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("quality_condition")
                .jsonObject
        assertEquals(0, value.getValue("quality_index").jsonPrimitive.int)
        assertEquals(251, value.getValue("comparison_value").jsonPrimitive.int)
        assertEquals("unknown_251", value.getValue("comparison").jsonPrimitive.content)
        assertEquals(JsonNull, value.getValue("quality_name"))
        assertEquals("null", value.getValue("quality_lookup").jsonPrimitive.content)
    }

    @Test
    fun spriteGraphsRemainRawBoundedAndScopedToSelectedWidgets() {
        val sprite =
            WidgetSprite(
                "raw.png",
                false,
                false,
                2,
                3,
                64,
                32,
                -0.5,
                0.0,
                0.0,
                listOf(Double.NaN, 0.0, 1.0, 1.0),
                0,
                -2,
            )
        val snapshot =
            GameSnapshot(
                "main_menu",
                true,
                1,
                listOf(
                    node(0)
                        .copy(
                            selected = true,
                            properties = WidgetProperties(icons = WidgetIcons(0, 0, -1)),
                        ),
                    node(0).copy(properties = WidgetProperties(icons = WidgetIcons(1, -1, -1))),
                ),
                sprites = listOf(sprite, sprite.copy(filename = "unselected.png", next = -1)),
            )
        val result = snapshot.uiJson(false, true)
        val resources = result.getValue("sprites").jsonArray
        assertEquals(1, resources.size)
        val value = resources.single().jsonObject
        assertEquals(-0.5, value.getValue("scale").jsonPrimitive.double)
        assertEquals(0, value.getValue("next").jsonPrimitive.int)
        assertEquals(JsonNull, value["extra"])
        assertTrue(value.getValue("extra_truncated").jsonPrimitive.boolean)
        assertEquals(JsonNull, value.getValue("tint").jsonObject["r"])
        assertTrue(value.getValue("tint").jsonObject.getValue("r_non_finite").jsonPrimitive.boolean)
        val icons =
            result
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("icons")
                .jsonObject
        assertEquals(icons["normal"], icons["hovered"])
        assertEquals(JsonNull, icons["disabled"])
    }

    @Test
    fun elementsDoNotInventMissingItemStateOrClampRawValues() {
        fun observe(element: WidgetElement) =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(node(0).copy(properties = WidgetProperties(element = element))),
            )
                .uiJson(false)["nodes"]!!
                .jsonArray
                .single()
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("element")
        assertEquals(JsonNull, observe(WidgetElement(false, null, null)))
        val stack = observe(WidgetElement(true, 4294967295L, null)).jsonObject
        assertEquals(4294967295L, stack.getValue("count").jsonPrimitive.long)
        assertEquals(JsonNull, stack["item"])
        val item = WidgetItem("Tool", true, 0.5, -0.25, Double.NaN)
        val direct = observe(WidgetElement(true, null, item)).jsonObject
        val nested = observe(WidgetElement(true, 0, item)).jsonObject.getValue("item")
        assertEquals(direct, nested)
        assertEquals(0.5, direct.getValue("health").jsonPrimitive.double)
        assertEquals(-0.25, direct.getValue("durability_left").jsonPrimitive.double)
        assertEquals(JsonNull, direct["magazine_left"])
        assertTrue(direct.getValue("magazine_left_non_finite").jsonPrimitive.boolean)
    }

    @Test
    fun qualityIdentityIsIndependentOfTheBasePrototype() {
        val snapshot =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(
                    node(0)
                        .copy(
                            properties =
                                WidgetProperties(
                                    prototype = WidgetPrototype(null, null),
                                    quality =
                                        WidgetPrototype("rare", "QualityPrototype", true, false),
                                )
                        ),
                    node(0)
                        .copy(properties = WidgetProperties(quality = WidgetPrototype(null, null))),
                    node(0),
                ),
            )
        val nodes = snapshot.uiJson(false).getValue("nodes").jsonArray
        val properties = nodes[0].jsonObject.getValue("properties").jsonObject
        assertEquals(JsonNull, properties.getValue("prototype").jsonObject["name"])
        val quality = properties.getValue("quality").jsonObject
        assertEquals("rare", quality["name"]!!.jsonPrimitive.content)
        assertTrue(quality["name_truncated"]!!.jsonPrimitive.boolean)
        assertNull(quality["type_truncated"])
        assertEquals(
            JsonNull,
            nodes[1]
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("quality")
                .jsonObject["name"],
        )
        assertNull(nodes[2].jsonObject["properties"])
    }

    @Test
    fun progressPropertiesKeepRawValuesAndUnknownDirections() {
        for (value in listOf(-0.25, 0.375, 2.5, Double.POSITIVE_INFINITY)) {
            val snapshot =
                GameSnapshot(
                    "in_game",
                    true,
                    1,
                    listOf(
                        node(0)
                            .copy(
                                properties =
                                    WidgetProperties(
                                        progress = WidgetProgress(value, "unknown_231", false)
                                    )
                            )
                    ),
                )
            val progress =
                snapshot
                    .uiJson(false)["nodes"]!!
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("progress")
                    .jsonObject
            assertEquals("unknown_231", progress["direction"]!!.jsonPrimitive.content)
            assertFalse(progress["has_text"]!!.jsonPrimitive.boolean)
            if (value.isFinite()) {
                assertEquals(value, progress["value"]!!.jsonPrimitive.double)
                assertNull(progress["value_non_finite"])
            } else {
                assertEquals(JsonNull, progress["value"])
                assertTrue(progress["value_non_finite"]!!.jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun numberPropertiesPreserveSuppressedUnknownAndNonFiniteValues() {
        val values =
            listOf(
                WidgetNumber(false, null, null, null, null),
                WidgetNumber(true, 42.5, true, false, false),
                WidgetNumber(true, null, false, true, false),
                WidgetNumber(true, Double.NaN, false, false, false),
            )
        val snapshot =
            GameSnapshot(
                "in_game",
                true,
                1,
                values.map { node(0).copy(properties = WidgetProperties(number = it)) },
            )
        val numbers =
            snapshot.uiJson(false).getValue("nodes").jsonArray.map {
                it.jsonObject.getValue("properties").jsonObject.getValue("number").jsonObject
            }
        assertEquals(JsonNull, numbers[0]["value"])
        assertNull(numbers[0]["unknown"])
        assertFalse(numbers[0].getValue("draw_requested").jsonPrimitive.boolean)
        assertEquals(42.5, numbers[1].getValue("value").jsonPrimitive.double)
        assertTrue(numbers[2].getValue("unknown").jsonPrimitive.boolean)
        assertEquals(JsonNull, numbers[3]["value"])
        assertTrue(numbers[3].getValue("value_non_finite").jsonPrimitive.boolean)
    }

    @Test
    fun prototypePropertiesDistinguishUnsupportedNullAndTruncatedIdentity() {
        val snapshot =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(
                    node(0),
                    node(0)
                        .copy(
                            properties = WidgetProperties(prototype = WidgetPrototype(null, null))
                        ),
                    node(0)
                        .copy(
                            properties =
                                WidgetProperties(
                                    prototype =
                                        WidgetPrototype(
                                            "partial",
                                            "ItemPrototype",
                                            nameTruncated = true,
                                        )
                                )
                        ),
                ),
            )
        val nodes = snapshot.uiJson(false).getValue("nodes").jsonArray.map { it.jsonObject }
        assertNull(nodes[0]["properties"])
        val empty = nodes[1].getValue("properties").jsonObject.getValue("prototype").jsonObject
        assertEquals(JsonNull, empty["name"])
        assertEquals(JsonNull, empty["native_type"])
        val partial = nodes[2].getValue("properties").jsonObject.getValue("prototype").jsonObject
        assertEquals("partial", partial.getValue("name").jsonPrimitive.content)
        assertEquals("ItemPrototype", partial.getValue("native_type").jsonPrimitive.content)
        assertTrue(partial.getValue("name_truncated").jsonPrimitive.boolean)
        assertNull(partial["type_truncated"])
    }

    @Test
    fun optionsPreserveEmptyLabelsAndIndependentTruncation() {
        val snapshot =
            GameSnapshot(
                "main_menu",
                true,
                1,
                listOf(
                    node(0)
                        .copy(
                            properties =
                                WidgetProperties(
                                    selectedIndex = 1,
                                    options =
                                        WidgetOptions(
                                            listOf(WidgetOption(""), WidgetOption("partial", true)),
                                            4,
                                        ),
                                )
                        )
                ),
            )
        val properties =
            snapshot
                .uiJson(false)
                .getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
                .getValue("properties")
                .jsonObject
        assertEquals(4, properties.getValue("options_total").jsonPrimitive.int)
        assertTrue(properties.getValue("options_truncated").jsonPrimitive.boolean)
        val options = properties.getValue("options").jsonArray
        assertEquals("", options[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(1, options[1].jsonObject.getValue("index").jsonPrimitive.int)
        assertTrue(options[1].jsonObject.getValue("text_truncated").jsonPrimitive.boolean)
    }

    private fun node(depth: Int, text: String = "") =
        WidgetSnapshot(depth, "class agui::Button", text, true, 1, 2, 3, 4, false)

    @Test
    fun typedPropertiesPreserveFalseEmptySelectionAndUnknownValues() {
        val snapshot =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(
                    node(0)
                        .copy(
                            properties =
                                WidgetProperties(
                                    "intermediate",
                                    false,
                                    -1,
                                    SliderProperties(12.5, 0.0, 100.0, Double.NaN),
                                )
                        )
                ),
            )
        val properties =
            snapshot
                .uiJson(false)["nodes"]!!
                .jsonArray
                .single()
                .jsonObject["properties"]!!
                .jsonObject
        assertEquals("intermediate", properties["check_state"]!!.jsonPrimitive.content)
        assertEquals(false, properties["toggled"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, properties["selected_index"])
        assertEquals(0, properties["index_base"]!!.jsonPrimitive.int)
        assertEquals(12.5, properties["value"]!!.jsonPrimitive.double)
        assertEquals(JsonNull, properties["step"])
    }

    @Test
    fun nestedWindowsAndSiblingParents() {
        val result =
            GameSnapshot(
                "main_menu",
                true,
                7,
                listOf(node(2, "Load"), node(1), node(2, "Cancel"), node(1), node(0)),
            )
                .uiJson(false)
        val nodes = result.getValue("nodes").jsonArray
        assertEquals(1, nodes[0].jsonObject.getValue("parent").jsonPrimitive.int)
        assertEquals(3, nodes[2].jsonObject.getValue("parent").jsonPrimitive.int)
        assertEquals(4, nodes[1].jsonObject.getValue("parent").jsonPrimitive.int)
        assertNull(nodes[4].jsonObject["parent"])
        assertNull(nodes[0].jsonObject["bounds"])
    }

    @Test
    fun truncatedTraversalDoesNotInventAnAncestor() {
        val result = GameSnapshot("in_game", true, 8, listOf(node(3)), true).uiJson(true)
        assertTrue(result.getValue("truncated").jsonPrimitive.boolean)
        assertNull(result.getValue("nodes").jsonArray[0].jsonObject["parent"])
    }

    @Test
    fun noPauseInferenceFromWorldPresence() {
        assertEquals(JsonNull, GameSnapshot("in_game", true, 1).statusJson(123)["paused"])
    }

    @Test
    fun visibilityRemainsAnObservationThroughProjectionAndTruncation() {
        val snapshot =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(
                    node(2, "Child")
                        .copy(visible = true, renderEnabled = true, hiddenBySearch = false),
                    node(1).copy(type = "class agui::VerticalFlow", visible = false),
                    node(0).copy(visible = true),
                ),
            )
        val json = snapshot.uiJson(false)
        assertEquals("own_widget_flags", json["visibility_basis"]!!.jsonPrimitive.content)
        assertEquals(3, json["nodes"]!!.jsonArray.size)
        assertEquals(
            true,
            json["nodes"]!!.jsonArray[0].jsonObject["visible"]!!.jsonPrimitive.boolean,
        )
        val partial = snapshot.copy(nodes = snapshot.nodes.take(1), truncated = true).uiJson(false)
        assertEquals(
            true,
            partial["nodes"]!!.jsonArray[0].jsonObject["visible"]!!.jsonPrimitive.boolean,
        )
        val unavailable =
            GameSnapshot(
                "main_menu",
                true,
                1,
                listOf(node(0)),
                visibilityUnavailableReason = "Missing metadata",
            )
                .uiJson(false)
        assertNull(unavailable["visibility_basis"])
        assertNull(unavailable["nodes"]!!.jsonArray[0].jsonObject["visible"])
        assertEquals(
            "Missing metadata",
            unavailable["visibility_unavailable_reason"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun nativeContainersAndParentsArePreservedWithoutInferredRoles() {
        val snapshot =
            GameSnapshot(
                "main_menu",
                true,
                1,
                listOf(
                    node(2, "Confirm"),
                    node(1).copy(type = "class agui::VerticalFlow"),
                    node(0).copy(type = "class agui::TopContainer"),
                ),
            )
        val json = snapshot.uiJson(false)
        val nodes = json.getValue("nodes").jsonArray
        assertEquals(3, nodes.size)
        assertEquals(0, nodes[0].jsonObject.getValue("id").jsonPrimitive.int)
        assertEquals(1, nodes[0].jsonObject.getValue("parent").jsonPrimitive.int)
        assertEquals(2, nodes[1].jsonObject.getValue("parent").jsonPrimitive.int)
        assertTrue(nodes.all { it.jsonObject["role"] == null })
    }

    @Test
    fun selectionRetainsEveryDescendantAndReportsOmissionCount() {
        val snapshot =
            GameSnapshot(
                "in_game",
                true,
                1,
                listOf(
                    node(3, "Button"),
                    node(2).copy(type = "class agui::VerticalFlow"),
                    node(1).copy(selected = true),
                    node(1, "Outside"),
                    node(0),
                ),
            )
        val selected = snapshot.uiJson(false, filtered = true)
        assertEquals(2, selected.getValue("selector_nodes_omitted").jsonPrimitive.int)
        val nodes = selected.getValue("nodes").jsonArray
        assertEquals(3, nodes.size)
        assertEquals(1, nodes[0].jsonObject.getValue("parent").jsonPrimitive.int)
        assertEquals(2, nodes[1].jsonObject.getValue("parent").jsonPrimitive.int)
        assertTrue(nodes[2].jsonObject.getValue("matched").jsonPrimitive.boolean)
        assertNull(nodes[2].jsonObject["parent"])
    }
}
