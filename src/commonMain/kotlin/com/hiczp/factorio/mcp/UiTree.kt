package com.hiczp.factorio.mcp

internal fun uiDepth(requested: Int?): Int = observationDepth(requested, MAX_OBSERVATION_DEPTH)

internal data class UiVisit(
    val source: Int,
    val parent: Int,
    val depth: Int,
    val siblingIndex: Int,
    val childrenTruncated: Boolean,
)

/** Projects complete postorder observations into independent, parent-first rooted views. */
internal class UiTree(nodes: List<WidgetSnapshot>) {
    private val children = Array(nodes.size) { mutableListOf<Int>() }
    private val parents = IntArray(nodes.size) { -1 }
    private val siblings = IntArray(nodes.size)
    private val order = mutableListOf<Int>()

    init {
        val pending = mutableListOf<Int>()
        nodes.forEachIndexed { index, node ->
            require(node.depth >= 0 && node.depth < nodes.size) { "Invalid UI ancestry" }
            while (pending.isNotEmpty() && nodes[pending.last()].depth > node.depth) {
                val child = pending.removeAt(pending.lastIndex)
                require(nodes[child].depth == node.depth + 1) { "Incomplete UI ancestry" }
                parents[child] = index
            }
            pending += index
        }
        require(pending.all { nodes[it].depth == 0 }) { "UI observation has a missing parent" }
        val roots = mutableListOf<Int>()
        nodes.indices.forEach { index ->
            val parent = parents[index]
            if (parent >= 0) {
                children[parent] += index
                siblings[index] = nodes[index].siblingIndex ?: children[parent].size
            } else {
                roots += index
                siblings[index] = nodes[index].siblingIndex ?: 0
            }
        }
        pending.clear()
        pending.addAll(roots.asReversed())
        while (pending.isNotEmpty()) {
            val index = pending.removeAt(pending.lastIndex)
            order += index
            pending.addAll(children[index].asReversed())
        }
        require(order.size == nodes.size) { "Cyclic or disconnected UI observation" }
    }

    fun project(nodes: List<WidgetSnapshot>, filtered: Boolean, maxDepth: Int): List<UiVisit> {
        require(maxDepth in 0..MAX_OBSERVATION_DEPTH)
        val roots = order.filter { if (filtered) nodes[it].selected else parents[it] < 0 }
        data class Pending(val source: Int, val parent: Int, val depth: Int)
        val output = mutableListOf<UiVisit>()
        val pending = mutableListOf<Pending>()
        roots.forEach { root ->
            pending += Pending(root, -1, 0)
            while (pending.isNotEmpty()) {
                val visit = pending.removeAt(pending.lastIndex)
                check(output.size < Int.MAX_VALUE) { "UI projection exceeds integer representation" }
                val id = output.size
                val ownChildren = children[visit.source]
                output += UiVisit(visit.source, visit.parent, visit.depth, siblings[visit.source],
                    nodes[visit.source].childrenTruncated || visit.depth == maxDepth && ownChildren.isNotEmpty())
                if (visit.depth < maxDepth) ownChildren.asReversed().forEach {
                    pending += Pending(it, id, visit.depth + 1)
                }
            }
        }
        return output
    }
}
