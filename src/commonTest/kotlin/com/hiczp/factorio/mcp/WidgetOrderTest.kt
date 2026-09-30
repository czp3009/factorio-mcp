package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class WidgetOrderTest {
    private fun widget(name: String, depth: Int) = WidgetSnapshot(depth, name, name, true, 1, 2, 3, 4, false)

    @Test
    fun preservesSiblingOrderAndPropertiesAcrossNestedBranches() {
        val root = widget("root", 0)
        val first = widget("first", 1)
        val grandchild = widget("grandchild", 2).copy(text = null, enabled = false, x = -123)
        val second = widget("second", 1)
        val nextRoot = widget("next-root", 0)
        assertEquals(
            listOf(grandchild, first, second, root, nextRoot),
            widgetsInPostorder(listOf(root, first, grandchild, second, nextRoot), listOf(-1, 0, 1, 0, -1))
        )
    }

    @Test
    fun acceptsTruncatedPrefixesWithoutCreatingUnobservedNodes() {
        val nodes = listOf(widget("root", 0), widget("branch", 1), widget("leaf", 2))
        assertEquals(nodes.reversed(), widgetsInPostorder(nodes, listOf(-1, 0, 1)))
        assertEquals(listOf(nodes[0]), widgetsInPostorder(nodes.take(1), listOf(-1)))
        assertEquals(emptyList(), widgetsInPostorder(emptyList(), emptyList()))
    }

    @Test
    fun rejectsBrokenRelationshipsInsteadOfInventingParents() {
        val nodes = listOf(widget("root", 0), widget("child", 1))
        assertFails { widgetsInPostorder(nodes, listOf(-1, 1)) }
        assertFails { widgetsInPostorder(nodes, listOf(-1, -1)) }
        assertFails { widgetsInPostorder(nodes, listOf(-1)) }
        assertFails { widgetsInPostorder(listOf(widget("orphan", 2)), listOf(-1)) }
        assertFails { widgetsInPostorder(nodes + widget("jump", 3), listOf(-1, 0, 1)) }
    }
}
