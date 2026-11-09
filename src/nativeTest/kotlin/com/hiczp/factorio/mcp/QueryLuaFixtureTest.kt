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
    fun recipeCatalogDefaultsPreserveNativeCategoryCollections() {
        for (selection in listOf(
            """{"kind":"recipes"}""",
            """{"kind":"prototypes","type":"recipe"}""",
        )) {
            run(
                query("""{"selection":$selection}"""),
                setup = """
                    local function recipe(class)
                      local values={object_name=class,name="fixture-recipe",enabled=true,hidden=false,energy=0.75,
                        categories={"opaque-a","opaque-b"},ingredients={},products={}}
                      if class=="LuaRecipe" then values.force=force end
                      return native(values)
                    end
                    me.force.recipes={["fixture-recipe"]=recipe("LuaRecipe")}
                    prototypes={recipe={["fixture-recipe"]=recipe("LuaRecipePrototype")}}
                """,
                assertions = """
                    assert(#captured.objects==1 and not captured.truncated)
                    local row=captured.objects[1]
                    assert(#row.attributes.categories==2)
                    assert(row.attributes.categories[1]=="opaque-a" and row.attributes.categories[2]=="opaque-b")
                    assert(next(row.read_status)==nil,"Unexpected recipe field status: "..tostring(next(row.read_status)))
                """,
            )
        }
    }

    @Test
    fun structureOnlyGuiReadDoesNotEvaluateOptionalPropertiesOrReturnChildArrays() {
        val api =
            RuntimeApi(Json.parseToJsonElement("""{"application":"factorio","classes":[{"name":"LuaGui","attributes":[{"name":"screen","read_type":"LuaGuiElement"}]},{"name":"LuaGuiElement","attributes":[{"name":"tags","read_type":"Tags"}]}]}""").jsonObject)
        val arguments = guiArguments(UiAction(emptyList(), guiFields = listOf("tags"), fields = emptySet()), api)
        run(
            arguments, setup = """
            optional_reads=0
            local values={object_name="LuaGuiElement",index=9,name="opaque",type="flow",children={}}
            setmetatable(values,{__index=function(_,key)
              if key=="tags" then optional_reads=optional_reads+1 error("optional property was read") end
            end})
            me.gui=native{object_name="LuaGui",screen=native(values)}
        """, invocation = """
            reader(1,"{}",string.char(2,0,0,0,1,0,0,0,9,0,0,0,1,0,0,0))
        """, assertions = """
            local item=captured.elements["9"]
            assert(item.index==9 and item.name=="opaque" and item.type=="flow")
            assert(item.children==nil and item.properties==nil and item.read_status==nil)
            assert(optional_reads==0)
        """, readerSource = guiObservationLua
        )
    }

    @Test
    fun finiteRegistriesAndMemberCatalogsAreCompleteUnlessPagedExplicitly() {
        val players = """
            local players=game.players
            for index=1,137 do
              players[index]=native{object_name="LuaPlayer",index=index,name="player-"..index,connected=true}
            end
        """.trimIndent()
        run(
            query("""{"selection":{"kind":"players"},"fields":["index","name"]}"""),
            setup = players,
            assertions = "assert(#captured.objects==137 and not captured.truncated and captured.next_offset==nil)",
        )
        run(
            query("""{"selection":{"kind":"players"},"fields":["index"],"limit":100}"""),
            setup = players,
            assertions = "assert(#captured.objects==100 and captured.truncated and captured.next_offset==100)",
        )
        run(
            query("""{"selection":{"kind":"players"},"fields":["index"],"offset":100}"""),
            setup = players,
            assertions = "assert(#captured.objects==37 and captured.objects[1].attributes.index==101 and not captured.truncated)",
        )
        run(
            query("""{"selection":{"kind":"inventories"}}"""),
            setup = """
                defines={inventory={}}
                for index=1,137 do defines.inventory["inventory_"..index]=index end
                me.get_inventory=function(index)
                  local inventory=native{object_name="LuaInventory",index=index,name="inventory_"..index,
                    supports_filters=function() return false end,supports_bar=function() return false end}
                  debug.getmetatable(inventory).__len=function() return 0 end
                  return inventory
                end
            """.trimIndent(),
            assertions = "assert(#captured.objects==137 and not captured.truncated)",
        )
        val arguments = query("""{"selection":{"kind":"inspect","target":{"kind":"game"}},"mode":"members"}""")
        run(
            JsonObject(arguments + ("api" to buildJsonObject {
                putJsonObject("LuaGameScript") {
                    putJsonArray("attributes") { repeat(137) { add("field_$it") } }
                    putJsonArray("methods") {}
                }
            })),
            assertions = "assert(#captured.objects[1].members==137 and not captured.truncated and captured.next_offset==nil)",
        )
    }

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

    @Test
    fun selectedAttributesAndPlainValuesAreCompleteWithoutImplicitEntryPages() {
        val names = List(100) { "field_$it" }
        val api = buildJsonObject {
            putJsonObject("LuaPlayer") {
                putJsonArray("attributes") { names.forEach { add(it) } }
                putJsonArray("methods") {}
            }
        }
        for (explicit in listOf(false, true)) {
            val request = buildJsonObject {
                putJsonObject("selection") {
                    put("kind", "inspect")
                    putJsonObject("target") { put("kind", "player") }
                }
                if (explicit) putJsonArray("fields") { names.forEach { add(it) } }
            }
            run(
                JsonObject(parseWorldQuery(request).arguments + ("api" to api)),
                setup = "for i=0,99 do me['field_'..i]=i end",
                assertions = """
                    local row=captured.objects[1]
                    local count=0
                    for _ in pairs(row.attributes) do count=count+1 end
                    assert(count==100 and row.attributes.field_99==99)
                    assert(not row.truncated and row.next_offset==nil and row.has_more==nil)
                """
            )
        }
        val values =
            query("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"crafting_queue"}]}}""")
        run(
            JsonObject(values + ("api" to Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["crafting_queue"],"methods":[]}}"""))),
            setup = "me.crafting_queue={} for i=1,1000 do me.crafting_queue[i]={count=i} end",
            assertions = """
                local row=captured.objects[1]
                assert(#row.value==1000 and row.value[1000].count==1000)
                assert(row.entries==nil and not row.truncated and captured.has_more==nil)
            """
        )
    }

    @Test
    fun ordinaryTablesPreserveStoredObjectNamesAndNeverRunObservationCallbacks() {
        val api =
            Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["crafting_queue"],"methods":["get_main_inventory"]}}""")
        val setup = """
            callback_count=0
            local function callback() callback_count=callback_count+1 error("Observation invoked a callback") end
            me.crafting_queue=setmetatable({object_name="LuaPlayer",value="retained",[""]="empty",["a\0b"]="nul"},
              {__index=callback,__pairs=callback,__len=callback})
        """
        run(
            query("""{"selection":{"kind":"player"},"fields":["crafting_queue"]}"""), setup = setup,
            assertions = """
                local value=captured.objects[1].attributes.crafting_queue
                assert(value.object_name=="LuaPlayer" and value.value=="retained" and value.observation==nil)
                assert(callback_count==0)
            """
        )
        val selection = """{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"crafting_queue"}]}"""
        for (mode in listOf("values", "entries")) {
            run(
                JsonObject(query("""{"selection":$selection,"mode":"$mode"}""") + ("api" to api)), setup = setup,
                assertions = if (mode == "values") """
                    local value=captured.objects[1].value
                    assert(value.object_name=="LuaPlayer" and value.value=="retained" and value.observation==nil)
                    assert(callback_count==0)
                """ else """
                    local row=captured.objects[1]
                    assert(#row.entries==4 and row.total==4 and not row.has_more)
                    assert(row.entries[1].key=="" and row.entries[1].value=="empty" and callback_count==0)
                """
            )
        }
        for ((key, expected) in listOf("" to "empty", "a\u0000b" to "nul", "missing" to null)) {
            val request = buildJsonObject {
                putJsonObject("selection") {
                    put("kind", "inspect")
                    putJsonObject("target") { put("kind", "player") }
                    putJsonArray("path") {
                        addJsonObject { put("property", "crafting_queue") }
                        addJsonObject { put("index", key) }
                    }
                }
            }
            run(
                JsonObject(parseWorldQuery(request).arguments + ("api" to api)), setup = setup,
                assertions = "assert(callback_count==0)\n" + if (expected == null)
                    "assert(captured.objects[1].availability=='nil')" else
                    "assert(captured.objects[1].value=='$expected')"
            )
        }
        val fakeMethod =
            query("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"crafting_queue"},{"method":"get_main_inventory"}]}}""")
        run(
            JsonObject(fakeMethod + ("api" to api)),
            setup = setup + "\nrawset(me.crafting_queue,'get_main_inventory',function() callback_count=callback_count+1 end)",
            invocation = "local ok,message=pcall(reader,1,'{}',1,800,600,-8,-8,8,8,'') assert(not ok and string.find(message,'not an admitted query',1,true))",
            assertions = "assert(callback_count==0)"
        )
    }

    @Test
    fun nestedDepthOmissionAggregatesWithoutChangingSelectionOrCollectionCompleteness() {
        val setup = "me.crafting_queue={empty={},branch={child=1}}"
        run(
            query("""{"selection":{"kind":"player"},"fields":["crafting_queue"],"depth":1}"""), setup = setup,
            assertions = """
                local value=captured.objects[1].attributes.crafting_queue
                assert(next(value.empty)==nil and value.branch.children_truncated==true)
                assert(captured.depth_truncated==true and captured.truncated==true and captured.has_more==nil)
            """
        )
        val api = Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["crafting_queue"],"methods":[]}}""")
        val request =
            query("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"crafting_queue"}]},"mode":"entries","depth":0}""")
        run(
            JsonObject(request + ("api" to api)), setup = setup,
            assertions = """
                local row=captured.objects[1]
                assert(row.total==2 and row.has_more==false and row.truncated==true and row.depth_truncated==true)
                assert(captured.truncated==true and captured.has_more==false and captured.next_offset==nil)
            """
        )
    }

    @Test
    fun tableDepthGuardIsIterativeAndTreatsTheHardDepthAsNormalTruncation() {
        val api = Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["crafting_queue"],"methods":[]}}""")
        val request =
            query("""{"selection":{"kind":"inspect","target":{"kind":"player"}},"fields":["crafting_queue"],"depth":2147483647}""")
        run(
            JsonObject(request + ("api" to api)), setup = """
            me.crafting_queue={}
            local current=me.crafting_queue
            for i=1,66000 do current.child={};current=current.child end
        """, assertions = """
            local value=captured.objects[1].attributes.crafting_queue
            for i=1,65535 do value=value.child end
            assert(value.reason=="depth_bound" and value.children_truncated==true)
            assert(captured.truncated==true and captured.depth_truncated==true)
        """
        )
    }

    @Test
    fun batchedReadsPreserveRootsRepetitionsAndIndependentCollectionBounds() {
        run(
            query(
                """{"selection":{"kind":"entities","position":{"x":0,"y":0},"radius":10},
          "fields":["crafting_speed"],"reads":[
            {"path":[{"method":"get_output_inventory"},{"method":"get_contents"}],"mode":"entries","limit":1},
            {"path":[{"method":"get_detailed_contents"}]},
            {"path":[{"property":"crafting_speed"}]},
            {"path":[{"property":"crafting_speed"}]}
          ]}"""
            ), setup = """
            local values={}
            for i=1,2 do
              values[i]=native{object_name="LuaEntity",name="opaque",type="assembling-machine",unit_number=i,
                surface=native_surface,position={x=i,y=0},direction=0,crafting_speed=i/2,
                get_output_inventory=function() return native{object_name="LuaInventory",valid=true,
                  get_contents=function() return {{name="a",count=i},{name="b",count=2*i}} end} end}
            end
            surface.find_entities_filtered=function(filter)
              assert(filter.limit==129)
              return values
            end
        """, assertions = """
            assert(#captured.objects==2 and captured.selection_complete==true)
            assert(captured.truncated==true and captured.has_more==nil)
            for i,row in ipairs(captured.objects) do
              assert(row.reference.unit_number==i and row.reference.position.x==i)
              assert(row.attributes.crafting_speed==i/2 and #row.reads==4)
              assert(#row.reads[1].entries==1 and row.reads[1].has_more==true and row.reads[1].next_offset==1)
              assert(row.reads[2].read_status.status=="error")
              assert(row.reads[3].value==i/2 and row.reads[4].value==i/2)
            end
        """
        )
    }

    @Test
    fun declaredInterfacesFollowPlainContainerPathsWithoutRenamingNativeObjects() {
        val api = RuntimeApi(
            Json.parseToJsonElement(
                """{"application":"factorio","classes":[
          {"name":"LuaPlayer","attributes":[{"name":"groups","read_type":{"complex_type":"array","value":{"complex_type":"table","parameters":[{"name":"stats","type":"LuaFlowStatistics"}]}}}]},
          {"name":"LuaFlowStatistics","attributes":[{"name":"valid","read_type":"boolean"}]}
        ]}"""
            ).jsonObject
        )
        val request = parseWorldQuery(
            Json.parseToJsonElement(
                """{"selection":{"kind":"inspect","target":{"kind":"player"},
          "path":[{"property":"groups"},{"index":1},{"property":"stats"}]},"fields":["valid"],
          "reads":[{"path":[{"property":"valid"}]}]}"""
            ).jsonObject
        )
        run(
            api.prepare(request).arguments, setup = """
            me.groups={{stats=native{object_name="ConcreteStatistics",valid=true}}}
        """, assertions = """
            local row=captured.objects[1]
            assert(row.object_name=="ConcreteStatistics" and row.api_class=="LuaFlowStatistics")
            assert(row.reference.object_name=="ConcreteStatistics" and row.reference.members_available==true)
            assert(row.attributes.valid==true and row.reads[1].value==true)
        """
        )
    }

    @Test
    fun iteratorPagesOwnOnlyFreshLocalIterationAndDoNotInvokeOtherCallOperators() {
        val api = RuntimeApi(
            Json.parseToJsonElement(
                """{"application":"factorio","classes":[
          {"name":"LuaSurface","methods":[{"name":"get_chunks","return_values":[{"order":0,"type":"LuaChunkIterator"}]}]},
          {"name":"LuaChunkIterator","operators":[{"name":"call","parameters":[],"return_values":[{"type":"Any"}]}]}
        ]}"""
            ).jsonObject
        )
        val request = parseWorldQuery(
            Json.parseToJsonElement(
                """{"selection":{"kind":"inspect","target":{"kind":"surface"},"path":[{"method":"get_chunks"}]},
          "mode":"entries","offset":1,"limit":2,"reads":[{"path":[{"property":"x"}]}]}"""
            ).jsonObject
        )
        run(
            api.prepare(request).arguments, setup = """
            calls=0
            surface.get_chunks=function()
              local iterator=native{object_name="LuaChunkIterator"}
              debug.getmetatable(iterator).__call=function()
                calls=calls+1
                if calls<=5 then return {x=calls,y=-1} end
              end
              return iterator
            end
        """, assertions = """
            local row=captured.objects[1]
            assert(calls==4 and #row.entries==2 and row.total==nil)
            assert(row.entries[1].index==2 and row.entries[1].reads[1].value==2)
            assert(row.entries[2].index==3 and row.has_more==true and row.next_offset==3)
        """
        )
    }

    @Test
    fun fieldProjectionNeverInvokesMethodsOrPlainTableMetamethods() {
        val api = Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["name"],"methods":[]}}""")
        val request = query("""{"selection":{"kind":"player"},"fields":["request_translation"]}""")
        run(
            JsonObject(request + ("api" to api)), setup = """
            calls=0
            me.request_translation=function() calls=calls+1 error("Mutation must not execute") end
        """, assertions = """
            assert(calls==0 and captured.objects[1].read_status.request_translation.status=="error")
        """
        )
    }

    @Test
    fun catalogPositionsAreNotAssumedToHaveWorldSurfaces() {
        val api = RuntimeApi(
            Json.parseToJsonElement(
                """{"application":"factorio","classes":[
          {"name":"LuaPrototypes","attributes":[{"name":"space_location","read_type":{"complex_type":"LuaCustomTable","key":"string","value":"LuaSpaceLocationPrototype"}}]},
          {"name":"LuaSpaceLocationPrototype","attributes":[{"name":"name","read_type":"string"},{"name":"position","read_type":"Any"}]},
          {"name":"LuaCustomTable","operators":[{"name":"index","read_type":"Any"}]}
        ]}"""
            ).jsonObject
        )
        val request =
            parseWorldQuery(Json.parseToJsonElement("""{"selection":{"kind":"prototypes","type":"space_location","names":["opaque"]},"fields":["name","position"]}""").jsonObject)
        run(
            api.prepare(request).arguments, setup = """
            prototypes=native{object_name="LuaPrototypes",space_location=native{object_name="LuaCustomTable",
              opaque=native{object_name="LuaSpaceLocationPrototype",name="opaque",position={x=1,y=2}}}}
        """, assertions = """
            local row=captured.objects[1]
            assert(row.attributes.name=="opaque" and row.attributes.position.x==1 and row.visibility==nil)
        """
        )
    }

    @Test
    fun arbitraryNativeCallOperatorsAreNeverConsumedAsQueryIterators() {
        val api = RuntimeApi(
            Json.parseToJsonElement(
                """{"application":"factorio","classes":[
          {"name":"LuaPlayer","attributes":[{"name":"random","read_type":"LuaRandomGenerator"}]},
          {"name":"LuaRandomGenerator","operators":[{"name":"call","parameters":[]}]}
        ]}"""
            ).jsonObject
        )
        val request = parseWorldQuery(
            Json.parseToJsonElement(
                """{"selection":{"kind":"player"},"fields":[],
          "reads":[{"path":[{"property":"random"}],"mode":"entries"}]}"""
            ).jsonObject
        )
        run(
            api.prepare(request).arguments, setup = """
            calls=0
            local random=native{object_name="LuaRandomGenerator"}
            debug.getmetatable(random).__call=function() calls=calls+1 return 1 end
            me.random=random
        """, assertions = """
            assert(calls==0 and captured.objects[1].reads[1].read_status.status=="error")
        """
        )
    }

    private fun run(
        arguments: JsonObject,
        setup: String = "",
        assertions: String,
        readerSource: String = worldQueryLua,
        invocation: String = "reader(1,\"{}\",1,800,600,-8,-8,8,8,\"\")",
    ) {
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
                        if not arguments.api then
                          arguments.api=fixture_catalog(arguments.fields or {},${
                            lua(buildJsonObject {
                                inspectionMethods.forEach {
                                    put(
                                        it,
                                        true
                                    )
                                }
                            })
                        })
                        end
                        helpers={json_to_table=function() return arguments end,table_to_json=function(value) captured=value return "{}" end}
                        local function reader(...)
                        $readerSource
                        end
                        $invocation
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
    fun guiReaderPreservesRawValuesAndMatchesWithoutInvokingCallbacks() {
        val attributes = listOf(
            "object_name", "name", "type", "index", "parent", "children", "caption",
            "tooltip", "elem_tooltip", "tags", "style", "sprite", "toggled", "auto_toggle", "number", "valid"
        )
        val api = RuntimeApi(buildJsonObject {
            put("application", "factorio")
            putJsonArray("classes") {
                for ((name, fields) in listOf(
                    "LuaGuiElement" to attributes, "LuaGui" to listOf("screen"),
                    "LuaStyle" to listOf("name")
                )) addJsonObject {
                    put("name", name)
                    putJsonArray("attributes") {
                        fields.forEach { field ->
                            addJsonObject {
                                put("name", field)
                                put("read_type", "any")
                            }
                        }
                    }
                }
            }
        })
        val selector =
            parseSelector(Json.parseToJsonElement("""{"path":[{"axis":"descendant","match":{"gui":{"sprite":"item/fixture","tags":{"enabled":false,"nested":[1,2]}}}}]}"""))
        assertEquals(
            attributes.sorted(), guiArguments(UiAction(selector, guiFields = listOf("*")), api)
                .getValue("fields").jsonArray.map { it.stringArgument() })
        run(
            guiArguments(
                UiAction(
                    selector,
                    guiFields = attributes.filter { it !in setOf("parent", "children") },
                    guiDepth = 2
                ), api
            ),
            setup = """
                local root_fields={object_name="LuaGuiElement",index=0,name="",type="flow",children={}}
                local root=native(root_fields)
                local child=native{object_name="LuaGuiElement",index=9,name="opaque",type="sprite-button",parent=root,children={},
                  sprite="item/fixture",toggled=true,auto_toggle=false,caption={"", "[item=fixture]", "Original"},
                  tooltip={"fixture.dynamic"},tags={enabled=false,nested={1,2},object_name="opaque-tag",deep={more={value=5}}},
                  style=native{object_name="LuaStyle",name="fixture-style"}}
                root_fields.children={child}
                me.gui=native{object_name="LuaGui",screen=root}
                local function word(value)
                  local bytes={}
                  for i=1,4 do bytes[i]=string.char(value%256) value=math.floor(value/256) end
                  return table.concat(bytes)
                end
                local function payload(phase,rows)
                  local values={word(phase),word(#rows)}
                  for _,row in ipairs(rows) do values[#values+1]=word(row[1]) values[#values+1]=word(row[2]) end
                  return table.concat(values)
                end
            """,
            invocation = """
                assert(reader(1,"{}",payload(1,{{0,1},{9,1},{0,0}}))=="010")
                reader(1,"{}",payload(2,{{9,1}}))
            """,
            assertions = """
                local item=captured.elements["9"]
                assert(item.index==9 and item.parent==0 and item.name=="opaque" and #item.children==0)
                assert(item.properties.toggled==true and item.properties.auto_toggle==false)
                assert(item.properties.tags.object_name=="opaque-tag" and item.properties.tags.enabled==false)
                assert(item.properties.tags.deep.more.reason=="depth_bound")
                assert(item.properties.caption[2]=="[item=fixture]" and item.properties.tooltip[1]=="fixture.dynamic")
                assert(item.properties.style.name=="fixture-style" and item.properties.style.observation=="reference")
                assert(item.read_status.number.status=="nil")
            """,
            readerSource = guiObservationLua,
        )
    }

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
            query(
                """{"selection":{"kind":"quickbar","slots":[
              {"page_index":1,"slot_index":1},{"page_index":1,"slot_index":7},
              {"page_index":2,"slot_index":1},{"page_index":2,"slot_index":2},
              {"page_index":256,"slot_index":256}],"screen_pages":[2]}}"""
            ),
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
            parseWorldOverview(
                Json.parseToJsonElement(
                    """{$area,"group_by":["position"],"aggregates":[
              {"operation":"sum","field":"amount"},{"operation":"min","field":"amount"},
              {"operation":"max","field":"amount"},{"operation":"sum","field":"health"}]}"""
                ).jsonObject
            ).arguments,
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
    fun scalarInspectionReturnsTheSelectedValueWithoutEntryPaging() {
        val api =
            RuntimeApi(
                Json.parseToJsonElement(
                    """{"application":"factorio","classes":[{"name":"LuaPlayer","attributes":[{"name":"name","read_type":"string"}]}]}"""
                )
                    .jsonObject
            )
        val request = parseWorldQuery(
            Json.parseToJsonElement(
                """{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"name"}]}}"""
            ).jsonObject
        )
        run(
            api.prepare(request).arguments, assertions = """
            local item=captured.objects[1]
            assert(item.value=="self" and not item.truncated)
            assert(item.offset==nil and item.total==nil and item.has_more==nil)
        """
        )
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
        val entries =
            query("""{"selection":{"kind":"inspect","target":{"kind":"player"},"path":[{"property":"payload"}]},"mode":"entries","offset":65536,"limit":10000}""")
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
        val values =
            query("""{"selection":{"kind":"inspect","target":{"kind":"player"}},"fields":["payload"],"depth":8}""")
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
    fun plainTablePreviewDoesNotInvokePairsMetamethodsOrDeduplicateSharedAttributes() {
        val api = Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["payload","other"],"methods":[]}}""")
        val values =
            query("""{"selection":{"kind":"inspect","target":{"kind":"player"}},"fields":["payload","other"]}""")
        run(
            JsonObject(values + ("api" to api)),
            setup = """
                local shared=setmetatable({value="retained"},{__pairs=function(value)
                  error("plain observation invoked __pairs")
                end})
                me.payload=shared
                me.other=shared
            """,
            assertions = """
                local row=captured.objects[1]
                assert(row.attributes.payload.value=="retained")
                assert(row.attributes.other.value=="retained")
            """,
        )
    }

    @Test
    fun plainTablePreviewsIdentifyLongCyclesKeepSharedBranchesAndLeaveBoundaryLeavesUnmarked() {
        val setup = """
            local nodes={}
            for i=1,257 do nodes[i]={original=i} end
            for i=1,257 do nodes[i].next=nodes[i%257+1] end
            local shared=setmetatable({value="unchanged"},{__pairs=function() error("unsafe iterator") end})
            me.payload={cycle=nodes[1],left=shared,right=shared,empty={},branch={child={value="deep"}}}
        """
        val deepAssertions = """
            local value=payload.cycle
            for i=1,257 do assert(value.original==i);value=value.next end
            assert(value.reason=="cycle" and value.children_truncated==nil)
            assert(payload.left.value=="unchanged" and payload.right.value=="unchanged")
            assert(next(payload.empty)==nil)
        """
        val boundaryAssertions = """
            assert(next(payload.empty)==nil)
            assert(payload.branch.reason=="depth_bound" and payload.branch.children_truncated==true)
        """
        val playerApi = Json.parseToJsonElement("""{"LuaPlayer":{"attributes":["payload"],"methods":[]}}""")
        val guiApi =
            RuntimeApi(Json.parseToJsonElement("""{"application":"factorio","classes":[{"name":"LuaGui","attributes":[{"name":"screen","read_type":"LuaGuiElement"}]},{"name":"LuaGuiElement","attributes":[{"name":"tags","read_type":"Tags"}]}]}""").jsonObject)
        for ((depth, assertions) in listOf(1024 to deepAssertions, 1 to boundaryAssertions)) {
            val arguments =
                query("""{"selection":{"kind":"inspect","target":{"kind":"player"}},"fields":["payload"],"depth":$depth}""")
            run(
                JsonObject(arguments + ("api" to playerApi)), setup = setup,
                assertions = "local payload=captured.objects[1].attributes.payload\n$assertions"
            )
            run(
                guiArguments(UiAction(emptyList(), guiFields = listOf("tags"), guiDepth = depth), guiApi),
                setup = """
                    $setup
                    me.gui=native{object_name="LuaGui",screen=native{object_name="LuaGuiElement",index=9,
                      name="opaque",type="flow",children={},tags=me.payload}}
                """,
                invocation = "reader(1,\"{}\",string.char(2,0,0,0,1,0,0,0,9,0,0,0,1,0,0,0))",
                assertions = "local payload=captured.elements[\"9\"].properties.tags\n$assertions",
                readerSource = guiObservationLua
            )
        }
    }

    @Test
    fun overviewScansAllRequestedCellsWithoutASharedCandidateBudget() {
        val request =
            parseWorldOverview(Json.parseToJsonElement("""{"area":{"left_top":{"x":0,"y":0},"right_bottom":{"x":19,"y":19}},"cell_size":1,"cell_limit":1000,"entity_limit":1000}""").jsonObject)
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

    @Test
    fun spatialTileBoundsReportIncompleteSelectionsWithoutContinuations() {
        val setup = """
            local reads=0
            surface.get_tile=function(x,y)
              reads=reads+1
              assert(reads<=3,"Observation read beyond its request")
              return native{object_name="LuaTile",name="raw",position={x=x,y=y},surface=native_surface}
            end
        """
        run(
            query("""{"selection":{"kind":"tiles","area":{"left_top":{"x":-2,"y":-1},"right_bottom":{"x":2,"y":2}}},"limit":3,"fields":["position"]}"""),
            setup = setup,
            assertions = """
                assert(captured.total==12 and #captured.objects==3 and captured.truncated)
                assert(captured.objects[1].attributes.position.x==-2 and captured.objects[1].attributes.position.y==-1)
                assert(captured.objects[2].attributes.position.x==-1 and captured.objects[2].attributes.position.y==-1)
                assert(captured.selection_complete==false and captured.next_position==nil and captured.has_more==nil)
            """,
        )
        run(
            query("""{"selection":{"kind":"tiles","area":{"left_top":{"x":999999,"y":999999},"right_bottom":{"x":1000000,"y":1000000}}},"limit":1,"fields":["position"]}"""),
            setup = setup,
            assertions = """
                assert(captured.total==1 and #captured.objects==1 and not captured.truncated and captured.selection_complete==true)
                assert(captured.next_position==nil and captured.objects[1].attributes.position.y==999999)
            """,
        )
    }

    @Test
    fun gridPagesPreserveRowCoordinatesAndSeparateCellContinuationFromEntityTruncation() {
        val setup = """
            surface.find_entities_filtered=function(filter)
              return {{position={x=filter.area.left_top.x+0.5,y=filter.area.left_top.y+0.5}}}
            end
            surface.get_tile=function() return {name="raw"} end
        """
        val request =
            parseWorldOverview(Json.parseToJsonElement("""{"area":{"left_top":{"x":-2,"y":-1},"right_bottom":{"x":2,"y":2}},"cell_size":1,"start_cell":{"row":0,"column":3},"cell_limit":3,"group_by":[]}""").jsonObject)
        run(
            request.arguments, setup = setup, assertions = """
            assert(captured.total_cells==12 and #captured.objects==3 and captured.truncated)
            assert(captured.objects[1].row==0 and captured.objects[1].column==3)
            assert(captured.objects[2].row==1 and captured.objects[2].column==0)
            assert(captured.next_cell.row==1 and captured.next_cell.column==2)
        """
        )
        val large =
            parseWorldOverview(Json.parseToJsonElement("""{"area":{"left_top":{"x":-1000000,"y":-1000000},"right_bottom":{"x":1000000,"y":1000000}},"cell_size":1,"start_cell":{"row":1999999,"column":1999998},"cell_limit":1,"group_by":[]}""").jsonObject)
        run(
            large.arguments, setup = setup, assertions = """
            assert(captured.total_cells==4000000000000 and #captured.objects==1)
            assert(captured.objects[1].row==1999999 and captured.objects[1].column==1999998)
            assert(captured.next_cell.row==1999999 and captured.next_cell.column==1999999)
        """
        )
    }

    @Test
    fun guiCollectionsPageOriginalKeysAndSliceStoredTextWithoutCallbacks() {
        val api =
            RuntimeApi(Json.parseToJsonElement("""{"application":"factorio","classes":[{"name":"LuaGui","attributes":[{"name":"screen","read_type":"LuaGuiElement"}]},{"name":"LuaGuiElement","attributes":[{"name":"name","read_type":"string"},{"name":"index","read_type":"uint32"},{"name":"tags","read_type":"Tags"},{"name":"text","read_type":"string"}]}]}""").jsonObject)
        val setup = """
            local fields={object_name="LuaGuiElement",index=9,name="opaque",type="text-box",children={},
              tags={list={"same","same","last"}},text="界🚀尾"}
            local element=native(fields)
            me.gui=native{object_name="LuaGui",screen=element}
            local function word(value)
              local bytes={}
              for i=1,4 do bytes[i]=string.char(value%256) value=math.floor(value/256) end
              return table.concat(bytes)
            end
            local payload=word(2)..word(1)..word(9)..word(1)
        """
        for ((collection, assertions) in listOf(
            """{"source":"gui","path":[{"property":"tags"},{"index":"list"}],"offset":1,"limit":2}""" to """
              local page=captured.elements["9"].collection
              assert(page.total==3 and #page.entries==2 and page.entries[1].key==2)
              assert(page.entries[1].value=="same" and page.entries[2].value=="last")
              assert(page.next_offset==nil and not page.truncated and not page.has_more)
            """,
            """{"source":"gui","path":[{"property":"text"}],"offset":3,"limit":4}""" to """
              local page=captured.elements["9"].collection
              assert(page.value=="🚀" and page.total==10 and page.next_offset==7)
            """,
        )) {
            val request = UiAction(emptyList(), collection = parseUiCollection(Json.parseToJsonElement(collection)))
            run(guiArguments(request, api), setup, assertions, guiObservationLua, "reader(1,\"{}\",payload)")
        }
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
        local fixture_objects={}
        local fixture_active,fixture_fields,fixture_methods
        local function fixture_register(object,fields)
          if not fixture_active then return end
          local class=rawget(fields,"object_name")
          if not class then return end
          local entry=fixture_active[class] or {attributes={},methods={},indexed=false,length=false}
          local seen={}
          for _,name in ipairs(entry.attributes) do seen[name]=true end
          for _,name in ipairs(entry.methods) do seen[name]=true end
          local function add(key,value)
            if type(key)~="string" or seen[key] then return end
            seen[key]=true
            if type(value)=="function" then
              if fixture_methods[key] then entry.methods[#entry.methods+1]=key end
            else entry.attributes[#entry.attributes+1]=key end
          end
          for key,value in next,fields do add(key,value) end
          for _,key in ipairs(fixture_fields) do add(key,rawget(fields,key)) end
          local meta=debug.getmetatable(object)
          entry.length=entry.length or meta.__len~=nil or class=="LuaInventory" or class=="LuaTransportLine"
          entry.indexed=entry.length or class=="LuaCustomTable"
          table.sort(entry.attributes)
          table.sort(entry.methods)
          fixture_active[class]=entry
        end
        local function native(fields)
          local value=assert(io.tmpfile())
          local original=debug.getmetatable(value)
          fixture_objects[value]=fields
          debug.setmetatable(value,{__index=fields,__newindex=fields,__gc=function(item)
            debug.setmetatable(item,original)
            original.__gc(item)
          end})
          fixture_register(value,fields)
          return value
        end
        local function fixture_catalog(fields,methods)
          fixture_active={}
          fixture_fields=fields
          fixture_methods=methods
          for object,values in pairs(fixture_objects) do fixture_register(object,values) end
          return fixture_active
        end
        local surface={object_name="LuaSurface",index=1,name="fixture",valid=true,planet={object_name="LuaPlanet",name="fixture-planet"}}
        surface.is_chunk_generated=function() return true end
        local force=native{object_name="LuaForce",name="fixture-force",index=1,valid=true}
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
        game=native{object_name="LuaGameScript",tick=50,players={native_me,native_other},forces={[1]=force,["fixture-force"]=force},
          get_player=function(index) if index==1 or index=="self" then return native_me elseif index==2 or index=="other" then return native_other end end,
          get_surface=function() return native_surface end,get_entity_by_unit_number=function() return machine end}
        """
            .trimIndent()
}
