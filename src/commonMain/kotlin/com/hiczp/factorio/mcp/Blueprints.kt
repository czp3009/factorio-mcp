package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonObject

internal suspend fun GameProcess.importBlueprint(encoded: String?, data: JsonObject?, timeoutMillis: Int): String {
    require((encoded == null) != (data == null)) { "Specify either a blueprint string or blueprint data" }
    require((encoded ?: data.toString()).encodeToByteArray().size <= 24000) { "Blueprint input is too large" }
    val decode = if (encoded != null) """
        local encoded=${luaQuote(encoded)}
        assert(encoded:sub(1,1)=='0', 'unsupported blueprint string format')
        local decoded=assert(helpers.decode_string(encoded:sub(2)), 'invalid blueprint encoding')
        local document=assert(helpers.json_to_table(decoded), 'invalid blueprint JSON')
    """ else """
        local blueprint=assert(helpers.json_to_table(${luaQuote(data.toString())}), 'invalid blueprint data')
        $blueprintEncodingSource
    """
    return submitBlueprint(decode, timeoutMillis)
}

internal val blueprintEncodingSource = """
        blueprint.item='blueprint'
        if not blueprint.version then
            local major,minor,patch=helpers.game_version:match('^(%d+)%.(%d+)%.(%d+)')
            assert(major, 'cannot read the game version')
            blueprint.version=((tonumber(major)*65536+tonumber(minor))*65536+tonumber(patch))*65536
        end
        local document={blueprint=blueprint}
        local encoded=string.format('0%s',assert(helpers.encode_string(helpers.table_to_json(document))))
""".trimIndent()

private suspend fun GameProcess.submitBlueprint(decode: String, timeoutMillis: Int): String {
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('blueprint_import')
        return {responseType='blueprint_import',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=assert(__factorio_mcp_resident_v1).player()
            assert(p.character and not p.cursor_stack.valid_for_read and not p.cursor_ghost, 'a character with an empty cursor is required')
            $decode
            local blueprint=assert(document.blueprint, 'a single blueprint is required; books and planners are unsupported')
            local entities,tiles=blueprint.entities or {},blueprint.tiles or {}
            assert(#entities+#tiles>0 and #entities+#tiles<=128, 'blueprints must contain between 1 and 128 entities/tiles')
            for _,entity in ipairs(entities) do assert(prototypes.entity[entity.name], 'unknown entity prototype') end
            for _,tile in ipairs(tiles) do assert(prototypes.tile[tile.name], 'unknown tile prototype') end
            local deadline=b.native('now')+$timeoutMillis
            local character,surface=p.character,p.surface
            local stage,finished=0,false
            local closedAt
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            local function validate()
                assert(p.valid and p.connected and p.character==character and p.surface==surface, 'player, character or surface changed')
                assert(b.native('now')<deadline, 'blueprint operation timed out; inspect the target and cursor before retrying')
            end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                validate()
                if stage~=0 then return false end
                if p.opened_gui_type~=defines.gui_type.none then
                    if closedAt and game.tick<=closedAt then return false end
                    closedAt=game.tick
                    local result=b.native('close_gui')
                    if result=='waiting' then return false end
                    assert(result=='submitted',result)
                    return false
                end
                local result=b.native('blueprint_import',encoded)
                assert(result=='submitted',result)
                stage=1
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                validate()
                local stack=p.cursor_stack
                if stage==1 and stack.valid_for_read and stack.is_blueprint and stack.is_blueprint_setup() then
                    finish({label=stack.label,entities=#(stack.get_blueprint_entities() or {}),tiles=#(stack.get_blueprint_tiles() or {}),tick=game.tick},false)
                    return true
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}
