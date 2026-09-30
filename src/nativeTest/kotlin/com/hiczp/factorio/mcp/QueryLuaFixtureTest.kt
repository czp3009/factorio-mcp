@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Executes the real fixed reader against synthetic native-like objects, never a Factorio process.
 */
class QueryLuaFixtureTest {
    @Test
    fun cursorRecordsAndHandLocationsPreserveNativeReferences() {
        run(
            parseWorldQuery(
                Json.parseToJsonElement("""{"selection":{"kind":"player"}}""").jsonObject
            )
                .arguments,
            setup =
                """
                me.cursor_stack=native{object_name="LuaItemStack",valid_for_read=false}
                me.cursor_record=native{object_name="LuaRecord",valid=true,type="blueprint"}
                me.cursor_stack_temporary=true
                me.hand_location={inventory=1,slot=3}
            """,
            assertions =
                """
                local row=captured.objects[1]
                assert(row.attributes.cursor_stack.valid_for_read==false)
                assert(row.attributes.cursor_record.type=="blueprint")
                assert(row.attributes.cursor_stack_temporary==true)
                assert(row.attributes.hand_location.inventory==1 and row.attributes.hand_location.slot==3)
                assert(row.read_status.cursor_ghost.status=="nil")
            """,
        )
    }

    private fun run(arguments: JsonObject, setup: String = "", assertions: String) {
        val path =
            checkNotNull(
                getenv("FACTORIO_MCP_TEST_LUA_LIBRARY")?.toKString()?.takeIf { it.isNotBlank() }
            ) {
                "Run the platform Gradle test task or set FACTORIO_MCP_TEST_LUA_LIBRARY for a direct test run"
            }
        memScoped {
            val library = LuaFixtureLibrary(path)
            try {
                val create =
                    library.symbol("luaL_newstate")
                        .reinterpret<CFunction<() -> COpaquePointer?>>()
                val close =
                    library.symbol("lua_close")
                        .reinterpret<CFunction<(COpaquePointer?) -> Unit>>()
                val open =
                    library.symbol("luaL_openlibs")
                        .reinterpret<CFunction<(COpaquePointer?) -> Unit>>()
                val load =
                    library.symbol("luaL_loadbufferx")
                        .reinterpret<
                                CFunction<
                                            (
                                    COpaquePointer?,
                                    CPointer<ByteVar>?,
                                    ULong,
                                    CPointer<ByteVar>?,
                                    CPointer<ByteVar>?,
                                ) -> Int
                                        >
                                >()
                val call =
                    library.symbol("lua_pcallk")
                        .reinterpret<
                                CFunction<(COpaquePointer?, Int, Int, Int, Int, COpaquePointer?) -> Int>
                                >()
                val text =
                    library.symbol("lua_tolstring")
                        .reinterpret<
                                CFunction<
                                            (COpaquePointer?, Int, CPointer<ULongVar>?) -> CPointer<ByteVar>?
                                        >
                                >()
                val state = checkNotNull(create())
                try {
                    open(state)
                    val code =
                        """
                        $fixture
                        $setup
                        local arguments=${lua(arguments)}
                        helpers={json_to_table=function() return arguments end,table_to_json=function(value) captured=value return "{}" end}
                        local function reader(...)
                        $worldQueryLua
                        end
                        reader(1,"{}",1,800,600,-8,-8,8,8,"")
                        $assertions
                    """
                            .trimIndent()
                            .encodeToByteArray()
                    code.usePinned {
                        val loaded =
                            load(
                                state,
                                it.addressOf(0),
                                code.size.toULong(),
                                "fixture".cstr.ptr,
                                "t".cstr.ptr,
                            )
                        assertEquals(0, loaded, text(state, -1, null)?.toKString())
                    }
                    val result = call(state, 0, 0, 0, 0, null)
                    assertEquals(0, result, text(state, -1, null)?.toKString())
                } finally {
                    close(state)
                }
            } finally {
                library.close()
            }
        }
    }

    private fun query(json: String) =
        parseWorldQuery(Json.parseToJsonElement(json).jsonObject).arguments

    @Test
    fun discoversOtherPlayersAndTheirPositions() {
        run(
            query(
                """{"selection":{"kind":"players","connected":true},"fields":["index","name","position","physical_position","surface"],"limit":5}"""
            ),
            assertions =
                """
            assert(#captured.objects==2)
            assert(captured.objects[2].attributes.name=="other")
            assert(captured.objects[2].attributes.position.x==90)
            assert(captured.objects[2].attributes.surface.planet.name=="fixture-planet")
        """,
        )
    }

    @Test
    fun selectedPlayerAndInventoryOwnerDoNotDefaultToSelf() {
        run(
            query(
                """{"selection":{"kind":"player","player":"other"},"fields":["index","name","position"]}"""
            ),
            assertions =
                """
            assert(captured.objects[1].attributes.index==2)
            assert(captured.player.index==1)
        """,
        )
        run(
            query("""{"selection":{"kind":"inventories","owner":{"kind":"player","player":2}}}"""),
            setup =
                """
            defines={inventory={character_main=1}}
            other.get_inventory=function(index) assert(index==1) return nil end
            me.get_inventory=function() error("wrong player") end
        """,
            assertions = "assert(#captured.objects==0)",
        )
    }

    @Test
    fun overviewPassesTypeUnionAndNameIntersectionToNativeQuery() {
        val args =
            parseWorldOverview(
                Json.parseToJsonElement(
                    """{"detail":"entities","type":["transport-belt","mining-drill"],"name":["mod-drill"],"fields":["name"],"include":["recipe","fluids","filters"]}"""
                )
                    .jsonObject
            )
                .arguments
        run(
            args,
            setup =
                """
            surface.find_entities_filtered=function(filter)
              assert(filter.type[1]=="transport-belt" and filter.type[2]=="mining-drill")
              assert(filter.name[1]=="mod-drill" and filter.limit==65)
              return {machine}
            end
        """,
            assertions =
                """
            assert(#captured.objects==1)
            local item=captured.objects[1]
            assert(item.attributes.name=="mod-drill")
            assert(item.details.recipe.recipe.name=="mod-recipe")
            assert(item.details.fluids.water==42)
            assert(item.details.filters.slots[1].index==1)
        """,
        )
    }

    @Test
    fun worldQueryPassesCombinedFiltersBeforeApplyingTheResultLimit() {
        run(
            query(
                """{"selection":{"kind":"entities","area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":32,"y":32}},"type":["transport-belt","mining-drill"],"name":"mod-drill"},"fields":["name","type"],"limit":1}"""
            ),
            setup =
                """
                surface.find_entities_filtered=function(filter)
                  assert(filter.type[1]=="transport-belt" and filter.type[2]=="mining-drill")
                  assert(filter.name=="mod-drill" and filter.limit==2)
                  assert(filter.area.right_bottom.x==32)
                  return {machine}
                end
            """,
            assertions =
                """
                assert(#captured.objects==1 and not captured.truncated)
                assert(captured.objects[1].attributes.name=="mod-drill")
                assert(captured.objects[1].attributes.type=="mining-drill")
            """,
        )
    }

    @Test
    fun scalarInspectionHonorsPagination() {
        val api =
            RuntimeApi(
                Json.parseToJsonElement(
                    """{"application":"factorio","classes":[{"name":"LuaPlayer","attributes":[{"name":"name","read_type":"string"}]}]}"""
                )
                    .jsonObject
            )
        for (offset in 0..1) {
            val request =
                parseWorldQuery(
                    Json.parseToJsonElement(
                        """{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"name"}]},"offset":$offset}"""
                    )
                        .jsonObject
                )
            run(
                api.prepare(request).arguments,
                assertions =
                    """
                    local item=captured.objects[1]
                    assert(item.total==1 and not item.truncated)
                    assert(item.value==${if (offset == 0) "\"self\"" else "nil"})
                """,
            )
        }
    }

    @Test
    fun inspectionTraversesReferencesAndMultiReturnMethods() {
        val api =
            RuntimeApi(
                Json.parseToJsonElement(
                    """{"application":"factorio","classes":[
          {"name":"LuaGameScript","attributes":[{"name":"players","read_type":"LuaCustomTable"}]},
          {"name":"LuaCustomTable","operators":[{"name":"index","read_type":"LuaPlayer"}],"attributes":[]},
          {"name":"LuaPlayer","attributes":[{"name":"name","read_type":"string"},{"name":"missing","read_type":"string"}]},
          {"name":"LuaEntity","attributes":[],"methods":[{"name":"get_recipe","parameters":[]}]},
          {"name":"LuaQualityPrototype","attributes":[{"name":"name","read_type":"string"}]}
        ]}"""
                )
                    .jsonObject
            )
        val args =
            api.prepare(
                WorldQuery(
                    query(
                        """{"selection":{"kind":"inspect","target":{"kind":"game"},"path":[{"property":"players"},{"index":2}]},"fields":["name","missing"]}"""
                    )
                )
            )
                .arguments
        run(
            args,
            assertions =
                """
            assert(captured.objects[1].attributes.name=="other")
            assert(captured.objects[1].read_status.missing.status=="nil")
        """,
        )
        val recipe =
            api.prepare(
                WorldQuery(
                    query(
                        """{"selection":{"kind":"inspect","target":{"kind":"entities","unit_number":7},"path":[{"method":"get_recipe","result":2}]},"fields":["name"]}"""
                    )
                )
            )
                .arguments
        run(recipe, assertions = "assert(captured.objects[1].attributes.name=='normal')")
    }

    @Test
    fun collectionPagingAndMemberDiscoveryAreBounded() {
        val args =
            query(
                """{"selection":{"kind":"inspect","target":{"kind":"game"},"path":[{"property":"players"}]},"mode":"entries","limit":1}"""
            )
        val api =
            Json.parseToJsonElement(
                """{"LuaGameScript":{"attributes":["players"],"methods":[]},"LuaPlayer":{"attributes":["name","index"],"methods":[]}}"""
            )
        run(
            JsonObject(args + ("api" to api)),
            assertions =
                """
            assert(#captured.objects[1].entries==1)
            assert(captured.objects[1].truncated and captured.objects[1].next_offset==1)
            assert(captured.objects[1].entries[1].value.observation=="reference")
        """,
        )
    }

    private fun lua(value: JsonElement): String =
        when (value) {
            is JsonObject ->
                value.entries.joinToString(",", "{", "}") {
                    "[${lua(JsonPrimitive(it.key))}]=${lua(it.value)}"
                }

            is JsonArray -> value.joinToString(",", "{", "}") { lua(it) }
            JsonNull -> "nil"
            is JsonPrimitive ->
                if (value.isString)
                    value.content.encodeToByteArray().joinToString("", "\"", "\"") {
                        "\\${(it.toInt() and 255).toString().padStart(3, '0')}"
                    }
                else value.content
        }

    private val fixture =
        """
        local function native(fields)
          local value=assert(io.tmpfile())
          local gc=debug.getmetatable(value).__gc
          debug.setmetatable(value,{__index=fields,__gc=gc})
          return value
        end
        local surface={object_name="LuaSurface",index=1,name="fixture",valid=true,planet={object_name="LuaPlanet",name="fixture-planet"}}
        surface.is_chunk_generated=function() return true end
        local force={object_name="LuaForce",name="fixture-force",index=1}
        force.is_chunk_charted=function() return true end
        force.is_chunk_visible=function() return true end
        local native_surface=native(surface)
        local me={object_name="LuaPlayer",name="self",index=1,valid=true,connected=true,surface=native_surface,
          physical_surface=native_surface,position={x=0,y=0},physical_position={x=0,y=0},force=force}
        local other={object_name="LuaPlayer",name="other",index=2,valid=true,connected=true,surface=native_surface,
          physical_surface=native_surface,position={x=90,y=12},physical_position={x=9,y=1},force=force}
        local native_me,native_other=native(me),native(other)
        local quality=native{object_name="LuaQualityPrototype",name="normal",level=0}
        local machine=native{object_name="LuaEntity",name="mod-drill",type="mining-drill",unit_number=7,surface=native_surface,
          position={x=1,y=2},filter_slot_count=1,get_filter=function(index) return {name="iron-ore"} end,
          get_recipe=function() return native{object_name="LuaRecipe",name="mod-recipe",force=force},quality end,
          get_fluid_contents=function() return {water=42} end}
        game=native{object_name="LuaGameScript",tick=50,players={native_me,native_other},
          get_player=function(index) if index==1 or index=="self" then return native_me elseif index==2 or index=="other" then return native_other end end,
          get_surface=function() return native_surface end,get_entity_by_unit_number=function() return machine end}
        """
            .trimIndent()
}
