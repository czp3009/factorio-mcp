package com.hiczp.factorio.mcp

import kotlin.test.*

class StoredTooltipTest {
    @Test
    fun unavailableProvidersNeverReadTextAndStoredTextStaysUnchanged() {
        fun unread(): String = error("A provider must not be invoked")
        assertNull(widgetTooltip(0u, 0u, ::unread, ::unread))
        assertEquals("unsupported_provider", widgetTooltip(2u, 0u, ::unread, ::unread)?.status)
        assertEquals("unavailable", widgetTooltip(4u, 0u, ::unread, ::unread)?.status)
        val title = "[item=iron-plate] 原始标题"
        val text = "line one\nline two"
        assertEquals(
            WidgetTooltip("stored", title, text, true, true),
            widgetTooltip(3u, 3u, { title }, { text }),
        )
        assertFails { widgetTooltip(5u, 0u, ::unread, ::unread) }
        assertFails { widgetTooltip(3u, 4u, ::unread, ::unread) }
    }
}
