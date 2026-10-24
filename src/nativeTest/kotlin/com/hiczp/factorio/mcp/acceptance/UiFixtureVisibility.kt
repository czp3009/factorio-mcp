package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import com.hiczp.factorio.mcp.uiSelector
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

internal suspend fun McpHttpClient.fixtureVisible(): Boolean = tool("ui_read", buildJsonObject {
    put("selector", uiSelector("MCP action fixture", "agui::Window"))
}).toolValue().getValue("nodes").jsonArray.single {
    it.jsonObject["matched"]?.jsonPrimitive?.boolean == true
}.jsonObject.getValue("visible").jsonPrimitive.boolean

/** Test setup only: remove the disposable overlay before exercising world mouse input. */
internal suspend fun McpHttpClient.setFixtureVisible(visible: Boolean) {
    if (fixtureVisible() == visible) return
    tool("ui_action", buildJsonObject {
        put("action", "click")
        put("selector", uiSelector("MCP toggle fixture", "agui::TextButton"))
    }).toolValue()
    while (fixtureVisible() != visible) delay(20)
}
