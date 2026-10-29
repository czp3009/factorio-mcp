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
    fun catalogGroupsPreserveTheGamesConcreteObjectNames() {
        for (group in listOf("LuaItemGroup", "LuaItemSubGroup")) {
            run(
                query("""{"selection":{"kind":"prototypes","type":"item","names":["fixture-item"]},"fields":["group"]}"""),
                setup = """
                    prototypes={item={['fixture-item']=native{object_name="LuaItemPrototype",name="fixture-item",
                      group=native{object_name="$group",name="fixture-group"}}}}
                """,
                assertions = """
                    assert(captured.objects[1].attributes.group.object_name=="$group")
                    assert(captured.objects[1].attributes.group.name=="fixture-group")
                """,
            )
        }
    }

    @Test
    fun quickbarPreservesExplicitLocationsAndNativeSlotContents() {
        run(
            query("""{"selection":{"kind":"quickbar","slots":[
              {"page_index":1,"slot_index":1},{"page_index":1,"slot_index":7},
              {"page_index":2,"slot_index":1},{"page_index":2,"slot_index":2},
              {"page_index":256,"slot_index":256}],"screen_pages":[2]}}"""),
            setup = """
                me.quick_bar_width=7
                me.get_active_quick_bar_page=function(index) assert(index==2) return 4 end
                me.get_quick_bar_slot=function(page,slot)
                  assert(page>=1 and slot>=1 and slot<=7)
                  if page==1 and slot==1 then return {type="filter",filter={name="iron-plate",quality="uncommon"}} end
                  if page==1 and slot==7 then return {type="record",record=native{object_name="LuaRecord",valid=true,type="blueprint"}} end
                  if page==2 and slot==1 then return {type="item",item=native{object_name="LuaItem",valid=true,name="spidertron-remote",quality=quality}} end
                  if page==2 and slot==2 then return nil end
                  error("Invalid quickbar location")
                end
            """,
            assertions = """
                assert(captured.page_width==7)
                assert(captured.observation=="quickbar_slots")
                local rows=captured.objects
                assert(rows[1].attributes.value.filter.quality=="uncommon")
                assert(rows[2].attributes.value.record.type=="blueprint")
                assert(rows[3].page_index==2 and rows[3].slot_index==1)
                assert(rows[3].attributes.value.item.name=="spidertron-remote")
                assert(rows[4].read_status.value.status=="nil")
                assert(rows[5].read_status.value.status=="error")
                assert(captured.active_pages[1].attributes.page==4)
            """,
        )
    }

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
    fun overviewGroupsNativeValuesAndAggregatesOnlyExplicitNumericFields() {
        val setup = """
            surface.get_tile=function() return {name="fixture-tile"} end
            local entries={}
            for index=1,5 do
              local position=index%2==0 and {x=1,y=1} or {y=1,x=1}
              local fields={object_name="LuaEntity",name="same",type="fixture-" .. index,
                position=position,surface=native_surface,amount=index}
              fields.health=index==1 and 42 or index==2 and "opaque" or index==4 and math.huge or nil
              local entity=native(fields)
              if index==5 then
                local gc=debug.getmetatable(entity).__gc
                debug.setmetatable(entity,{__gc=gc,__index=function(_,field)
                  if field=="health" then error("unreadable native field") end
                  return fields[field]
                end})
              end
              entries[#entries+1]=entity
            end
            surface.find_entities_filtered=function() return entries end
        """.trimIndent()
        val area = """"area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":4,"y":4}},"cell_size":4"""
        run(
            parseWorldOverview(Json.parseToJsonElement("""{$area,"group_by":[],"aggregates":[]}""").jsonObject).arguments,
            setup = setup,
            assertions = """
                local group=captured.objects[1].entity_groups[1]
                assert(group.count==5 and next(group.attributes)==nil and #group.aggregates==0)
                assert(group.amount==nil and group.name==nil and group.type==nil)
            """.trimIndent(),
        )
        run(
            parseWorldOverview(Json.parseToJsonElement("""{$area,"group_by":["position"],"aggregates":[
              {"operation":"sum","field":"amount"},{"operation":"min","field":"amount"},
              {"operation":"max","field":"amount"},{"operation":"sum","field":"health"}]}""").jsonObject).arguments,
            setup = setup,
            assertions = """
                local groups=captured.objects[1].entity_groups
                assert(#groups==1 and groups[1].count==5)
                assert(groups[1].attributes.position.x==1 and groups[1].attributes.position.y==1)
                local aggregates=groups[1].aggregates
                assert(aggregates[1].value==15 and aggregates[1].numeric_values==5)
                assert(aggregates[2].value==1 and aggregates[3].value==5)
                local health=aggregates[4]
                assert(health.value==42 and health.numeric_values==1 and health.nil_values==1)
                assert(health.read_errors==1 and health.non_numeric_values==1 and health.non_finite_values==1)
            """.trimIndent(),
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

    @Test
    fun largeCatalogPagesAndRelatedValuesHaveNoHiddenScanOrProjectionBudget() {
        run(
            query("""{"selection":{"kind":"prototypes","type":"item"},"offset":66000,"limit":2000,"fields":["name"]}"""),
            setup = """
                prototypes={item={}}
                for i=1,70000 do
                  local name=string.format("item-%06d",i)
                  prototypes.item[name]={object_name="LuaItemPrototype",name=name}
                end
            """,
            assertions = """
                assert(#captured.objects==2000 and captured.truncated and captured.next_offset==68000)
                assert(captured.objects[1].attributes.name=="item-066001")
                assert(captured.objects[2000].attributes.name=="item-068000")
            """,
        )
        run(
            query("""{"selection":{"kind":"player"},"fields":["crafting_queue"]}"""),
            setup = """
                me.crafting_queue={}
                for i=1,40000 do me.crafting_queue[i]={index=i,count=1} end
            """,
            assertions = """
                local queue=captured.objects[1].attributes.crafting_queue
                assert(#queue==40000 and queue[40000].index==40000)
            """,
        )
    }

    @Test
    fun inspectionPagesBeyondTheOldScanBoundAndExpandsRequestedDepth() {
        val api = Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["payload"],"methods":[]}}""")
        val entries = query("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"payload"}]},"mode":"entries","offset":65536,"limit":10000}""")
        run(
            JsonObject(entries + ("api" to api)),
            setup = """
                me.payload={}
                for i=1,70000 do me.payload[i]=i end
            """,
            assertions = """
                local row=captured.objects[1]
                assert(row.total==70000 and #row.entries==4464 and not row.truncated)
                assert(row.entries[1].key==65537 and row.entries[4464].value==70000)
            """,
        )
        val values = query("""{"selection":{"kind":"inspect","target":{"kind":"player"}},"fields":["payload"],"depth":8,"limit":10000}""")
        run(
            JsonObject(values + ("api" to api)),
            setup = """
                local values={}
                for i=1,1000 do values[i]=string.rep("x",5000) end
                me.payload={a={b={c={d=values}}}}
                me.payload.cycle=me.payload
            """,
            assertions = """
                local payload=captured.objects[1].attributes.payload
                assert(#payload.a.b.c.d==1000 and #payload.a.b.c.d[1000]==5000)
                assert(payload.cycle.reason=="cycle")
            """,
        )
    }

    @Test
    fun failedPreviewDoesNotMarkAnotherAttributeAsCyclic() {
        val api = Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["payload","other"],"methods":[]}}""")
        val values = query("""{"selection":{"kind":"inspect","target":{"kind":"player"}},"fields":["payload","other"]}""")
        run(
            JsonObject(values + ("api" to api)),
            setup = """
                local first=true
                local shared=setmetatable({value="retained"},{__pairs=function(value)
                  if first then first=false; error("fixture read failure") end
                  return next,value,nil
                end})
                me.payload=shared
                me.other=shared
            """,
            assertions = """
                local row=captured.objects[1]
                assert(row.read_status.payload.status=="error")
                assert(row.attributes.other.value=="retained")
            """,
        )
    }

    @Test
    fun overviewScansAllRequestedCellsWithoutASharedCandidateBudget() {
        val request = parseWorldOverview(Json.parseToJsonElement("""{"area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":19,"y":19}},"cell_size":1,"entity_limit":1000}""").jsonObject)
        run(
            request.arguments,
            setup = """
                surface.find_entities_filtered=function(filter)
                  local entity={name="raw",type="fixture",position={x=filter.area.left_top.x+0.5,y=filter.area.left_top.y+0.5}}
                  local found={}
                  for i=1,20 do found[i]=entity end
                  return found
                end
                surface.get_tile=function() return {name="fixture-tile"} end
            """,
            assertions = """
                assert(#captured.objects==361 and not captured.truncated)
                for _,cell in ipairs(captured.objects) do
                  assert(cell.entity_scan=="complete" and cell.entities_observed==20)
                  assert(cell.entity_groups[1].count==20)
                end
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
