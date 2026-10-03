package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class LoadingPredicateTest {
    @Test
    fun ordersManagerPriorityByControlFlowRatherThanPointerLoadOrder() {
        val flow = X64ControlFlow(X64Instructions(machineCode("85 ff 74 03 eb 06 90 85 f6 74 04 90 ff d0 c3 c3")).all())
        val first = LoadingPredicate.Guard(7, 4, 2)
        val second = LoadingPredicate.Guard(15, 11, 9)
        assertEquals(listOf(1, 0), LoadingPredicate.managerOrder(flow, listOf(second, first), 12))
        assertFails { LoadingPredicate.managerOrder(flow, listOf(first.copy(zero = 4, nonzero = 7), second), 12) }
        assertFails { LoadingPredicate.managerOrder(flow, listOf(first, first), 12) }
        assertFails { LoadingPredicate.managerOrder(flow, listOf(second, first), 11) }
    }
}
