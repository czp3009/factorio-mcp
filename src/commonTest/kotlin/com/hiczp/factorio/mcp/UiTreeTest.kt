package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.test.*

class UiTreeTest {
    private fun snapshot(depths: List<Int>, selected: Set<Int> = emptySet()) = GameSnapshot(
        "main_menu", true, 1, depths.mapIndexed { index, depth ->
            WidgetSnapshot(depth, "OpaqueWidget", "source-$index", true, 0, 0, 1, 1, false,
                selected = index in selected)
        },
    )

    private fun JsonObject.rows() = getValue("nodes").jsonArray.map { it.jsonObject }

    @Test
    fun defaultRootUsesRelativeDepthAndOnlyMarksNonemptyBoundaryNodes() {
        val source = snapshot(listOf(3, 2, 2, 1, 1, 0))
        val root = source.uiJson(false, maxDepth = 0)
        assertEquals(0, root.rows().single().getValue("depth").intArgument())
        assertTrue(root.rows().single().getValue("children_truncated").booleanArgument())
        val shallow = source.uiJson(false, maxDepth = 1)
        assertEquals(listOf(0, 1, 1), shallow.rows().map { it.getValue("depth").intArgument() })
        assertTrue(shallow.rows()[1].getValue("children_truncated").booleanArgument())
        assertNull(shallow.rows()[2]["children_truncated"])
        assertFalse(snapshot(listOf(0)).uiJson(false, maxDepth = 0).getValue("truncated").booleanArgument())
    }

    @Test
    fun residentPrunedBranchesKeepOmittedChildrenAndOriginalSiblingPositions() {
        val source = snapshot(listOf(1, 0, 0), setOf(1, 2))
        val resident = source.copy(nodes = source.nodes.mapIndexed { index, node -> node.copy(
            siblingIndex = listOf(8, 3, 7)[index], childrenTruncated = index == 0,
        ) })
        val result = resident.uiJson(false, filtered = true, maxDepth = 2)
        assertEquals(listOf(0, 1, 0), result.rows().map { it.getValue("depth").intArgument() })
        assertEquals(listOf(3, 8, 7), result.rows().map { it.getValue("sibling_index").intArgument() })
        assertTrue(result.rows()[1].getValue("children_truncated").booleanArgument())
        assertNull(result.rows()[2]["children_truncated"])
        assertTrue(result.getValue("truncated").booleanArgument())
    }

    @Test
    fun overlappingMatchesRemainIndependentCompleteRootedViews() {
        val result = snapshot(listOf(2, 1, 0), setOf(1, 2)).uiJson(false, filtered = true)
        assertEquals(Json.parseToJsonElement("[0,3]"), result["roots"])
        assertEquals(listOf(0, 1, 2, 0, 1), result.rows().map { it.getValue("depth").intArgument() })
        assertEquals(listOf("source-2", "source-1", "source-0", "source-1", "source-0"),
            result.rows().map { it.getValue("text").stringArgument() })
        assertNull(result.rows()[3]["parent"])
        assertEquals(3, result.rows()[4].getValue("parent").intArgument())
    }

    @Test
    fun aWideLevelIsNeverClippedByTheFormerNodeBudget() {
        val result = snapshot(List(6000) { 1 } + 0).uiJson(false, maxDepth = 1, fields = emptySet())
        assertEquals(6001, result.rows().size)
        assertFalse(result.getValue("truncated").booleanArgument())
        assertEquals(6000, result.rows().last().getValue("sibling_index").intArgument())
        assertTrue(result.rows().drop(1).all { it.getValue("parent").intArgument() == 0 })
    }

    @Test
    fun hardDepthBehavesExactlyLikeAnExplicitDepthAndSerializesWithoutDeepJsonNesting() {
        val source = snapshot((MAX_OBSERVATION_DEPTH + 2 downTo 0).toList())
        val implicit = source.uiJson(false, fields = emptySet())
        val explicit = source.uiJson(false, maxDepth = MAX_OBSERVATION_DEPTH, fields = emptySet())
        assertEquals(explicit, implicit)
        assertEquals(explicit, source.uiJson(false, maxDepth = Int.MAX_VALUE, fields = emptySet()))
        assertEquals(MAX_OBSERVATION_DEPTH + 1, implicit.rows().size)
        assertTrue(implicit.rows().last().getValue("children_truncated").booleanArgument())
        assertEquals(implicit, Json.parseToJsonElement(implicit.toString()))
        val leaf = snapshot((MAX_OBSERVATION_DEPTH downTo 0).toList()).uiJson(false, fields = emptySet())
        assertNull(leaf.rows().last()["children_truncated"])
        assertFalse(leaf.getValue("truncated").booleanArgument())
    }

    @Test
    fun missingMatchesReturnAnEmptyForestAndInvalidAncestryIsRejected() {
        val empty = snapshot(listOf(1, 0)).uiJson(false, filtered = true)
        assertEquals(JsonArray(emptyList()), empty["nodes"])
        assertEquals(JsonArray(emptyList()), empty["roots"])
        assertFalse(empty.getValue("truncated").booleanArgument())
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(2, 0)).uiJson(false) }
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(-1)).uiJson(false) }
        assertFailsWith<IllegalArgumentException> { uiDepth(-1) }
    }
}
