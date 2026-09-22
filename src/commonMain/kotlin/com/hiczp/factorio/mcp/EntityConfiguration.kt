package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

private data class EntitySetting(
    val type: String,
    val entityTypes: Set<String>,
    val description: String,
    val values: List<String> = emptyList()
)

private val entitySettings = mapOf(
    "recipe" to EntitySetting("string", setOf("assembling-machine"), "Available normal-quality recipe name."),
    "stack_size" to EntitySetting(
        "integer",
        setOf("inserter"),
        "Hand stack size override; zero disables the override."
    ),
    "use_filters" to EntitySetting("boolean", setOf("inserter"), "Enable the existing item filters."),
    "filter_mode" to EntitySetting(
        "string",
        setOf("inserter"),
        "Existing filter interpretation.",
        listOf("whitelist", "blacklist")
    ),
    "input_priority" to EntitySetting(
        "string",
        setOf("splitter"),
        "Preferred input side.",
        listOf("left", "right", "none")
    ),
    "output_priority" to EntitySetting(
        "string",
        setOf("splitter"),
        "Preferred output side. Disabling priority also clears the splitter filter, following game rules.",
        listOf("left", "right", "none")
    ),
    "train_limit" to EntitySetting(
        "integer",
        setOf("train-stop"),
        "Maximum incoming trains; zero closes the stop to new trains."
    ),
    "use_transitional_requests" to EntitySetting(
        "boolean",
        setOf("rocket-silo"),
        "Automatically request platform cargo."
    ),
    "request_missing_construction_materials" to EntitySetting(
        "boolean",
        setOf("space-platform-hub"),
        "Automatically request missing construction materials."
    )
)

internal val entitySettingsSchema = buildJsonObject {
    put("type", "object")
    put("minProperties", 1)
    put("additionalProperties", false)
    putJsonObject("properties") {
        for ((name, setting) in entitySettings) putJsonObject(name) {
            put("type", setting.type)
            put("description", "${setting.description} Entity types: ${setting.entityTypes.joinToString()}.")
            if (setting.type == "integer") {
                put("minimum", 0); put("maximum", Int.MAX_VALUE)
            }
            if (setting.type == "string") put("minLength", 1)
            if (setting.values.isNotEmpty()) putJsonArray("enum") { setting.values.forEach { add(it) } }
        }
    }
}

private val entitySettingTypes = buildJsonObject {
    for ((name, setting) in entitySettings) putJsonArray(name) { setting.entityTypes.forEach { add(it) } }
}

internal suspend fun GameProcess.configureEntity(
    x: Double,
    y: Double,
    settings: JsonObject,
    timeoutMillis: Int
): String {
    require(x.isFinite() && y.isFinite()) { "Coordinates must be finite" }
    return configureEntitySettings("{x=$x,y=$y}", settings, timeoutMillis)
}

/** Use the same native recipe submission for both opened-machine and coordinate-based tools. */
internal suspend fun GameProcess.setRecipe(name: String, timeoutMillis: Int): String =
    configureEntitySettings("nil", buildJsonObject { put("recipe", name) }, timeoutMillis)

private suspend fun GameProcess.configureEntitySettings(
    position: String,
    settings: JsonObject,
    timeoutMillis: Int
): String {
    val supported = entitySettings.keys
    require(settings.isNotEmpty() && settings.keys.all { it in supported }) {
        "Supported direct settings: ${supported.joinToString()}. Other settings do not have a verified direct submission path."
    }
    for ((key, value) in settings) {
        val primitive = value as? JsonPrimitive ?: error("$key requires a scalar value")
        val definition = entitySettings.getValue(key)
        val valid = when (definition.type) {
            "string" -> primitive.isString && primitive.content.isNotBlank()
            "integer" -> !primitive.isString && primitive.intOrNull?.let { it >= 0 } == true
            "boolean" -> !primitive.isString && primitive.booleanOrNull != null
            else -> false
        }
        require(valid) { "$key requires ${definition.type}" }
        require(definition.values.isEmpty() || primitive.content in definition.values) {
            "$key must be one of ${definition.values.joinToString()}"
        }
    }
    val task = """
        local b=assert(__factorio_mcp_resident_v1)
        local complete=b.completion('configure_entity')
        return {responseType='configure_entity',ready=function() return not b.player_action and not b.research end,callback=function()
            local p=b.player()
            local position=$position
            local entity=p.opened
            if position then
                entity=nil
                for _,candidate in pairs(p.surface.find_entities_filtered{position=position}) do
                    if candidate~=p.character and candidate.type~='resource' and candidate.type~='entity-ghost' then
                        assert(not entity, 'target position is ambiguous');entity=candidate
                    end
                end
            end
            assert(entity and entity.object_name=='LuaEntity' and entity.force==p.force and p.can_reach_entity(entity), 'select a reachable friendly entity')
            local settings=helpers.json_to_table(${luaQuote(settings.toString())})
            local commands={}
            local function toggle(name,value,read)
                commands[#commands+1]={name=name,value=value,read=read,kind='toggle',choice=name=='filter_mode' and (value=='blacklist' and 'right' or 'left') or value}
            end
            local function number(name,value,read)
                commands[#commands+1]={name=name,value=value,read=read,kind='number'}
            end
            if settings.recipe then
                assert(entity.type=='assembling-machine', 'recipe requires an assembling machine')
                local recipe=assert(p.force.recipes[settings.recipe], 'recipe not found')
                assert(recipe.enabled and entity.prototype.crafting_categories[recipe.category], 'recipe is not available for this machine')
                local _,quality=entity.get_recipe()
                assert(not quality or quality.name=='normal', 'changing a non-normal quality recipe is not supported by this selector')
                commands[#commands+1]={name='recipe',value=settings.recipe,kind='recipe',read=function()
                    local current,quality=entity.get_recipe();return current and quality.name=='normal' and current.name
                end}
            end
            if settings.stack_size then
                assert(entity.type=='inserter', 'stack_size requires an inserter')
                local bonus=entity.prototype.uses_inserter_stack_size_bonus and (entity.prototype.bulk and p.force.bulk_inserter_capacity_bonus or p.force.inserter_stack_size_bonus) or 0
                assert(settings.stack_size<=1+(entity.prototype.inserter_stack_size_bonus or 0)+bonus, 'stack_size exceeds this inserter capacity')
                toggle('stack_size_enabled',settings.stack_size>0,function() return entity.inserter_stack_size_override>0 end)
                if settings.stack_size>0 then number('stack_size',settings.stack_size,function() return entity.inserter_stack_size_override end) end
            end
            if settings.train_limit then
                assert(entity.type=='train-stop', 'train_limit requires a train stop')
                toggle('train_limit_enabled',true,function() return entity.trains_limit<2^32-1 end)
                number('train_limit',settings.train_limit,function() return entity.trains_limit end)
            end
            if settings.use_filters~=nil then
                assert(entity.type=='inserter', 'use_filters requires an inserter')
                toggle('use_filters',settings.use_filters,function() return entity.use_filters end)
            end
            if settings.filter_mode then
                assert(entity.type=='inserter', 'filter_mode requires an inserter')
                assert(entity.inserter_filter_mode==settings.filter_mode or settings.use_filters==true or settings.use_filters==nil and entity.use_filters, 'enable filters before changing filter_mode')
                toggle('filter_mode',settings.filter_mode,function() return entity.inserter_filter_mode end)
            end
            for _,name in ipairs{'input_priority','output_priority'} do
                if settings[name] then
                    assert(entity.type=='splitter', 'priorities require a splitter')
                    local property=string.format('splitter_%s',name)
                    toggle(string.format('%s_enabled',name),settings[name]~='none',function() return entity[property]~='none' end)
                    if settings[name]~='none' then toggle(name,settings[name],function() return entity[property] end) end
                end
            end
            for _,entry in ipairs{
                {name='use_transitional_requests',entity_type='rocket-silo',section_type=defines.logistic_section_type.transitional_request_controlled},
                {name='request_missing_construction_materials',entity_type='space-platform-hub',section_type=defines.logistic_section_type.request_missing_materials_controlled}
            } do
                local value=settings[entry.name]
                if value~=nil then
                    assert(entity.type==entry.entity_type, string.format('%s requires %s',entry.name,entry.entity_type))
                    local sections=entity.get_logistic_sections()
                    local section
                    for _,candidate in pairs(sections.sections) do if candidate.type==entry.section_type then section=candidate;break end end
                    assert(section or entity.type=='space-platform-hub', 'automatic request section is unavailable')
                    local function current()
                        for _,candidate in pairs(entity.get_logistic_sections().sections) do
                            if candidate.type==entry.section_type then return candidate.active end
                        end
                        return false
                    end
                    commands[#commands+1]={name=entry.name,value=value,read=current,kind=entity.type=='space-platform-hub' and 'toggle' or 'section',index=section and section.index,count=sections.sections_count}
                end
            end
            local unchanged=true
            for _,command in ipairs(commands) do if command.read()~=command.value then unchanged=false end end
            assert(unchanged or p.opened==entity or not p.cursor_stack.valid_for_read and not p.cursor_ghost, 'clear the cursor before opening an entity')
            local index,stage,finished=1,0,false
            local opened=p.opened==entity
            local phaseTick=-1
            local operations
            local surface,character=p.surface,p.character
            local deadline=b.native('now')+$timeoutMillis
            local function finish(value,failed)
                if finished then return end
                finished=true;b.native('pointer_end');b.player_action=nil;complete(value,failed)
            end
            local function fail(reason) finish(reason,true) end
            local function validate()
                assert(p.valid and p.connected and p.surface==surface and p.character==character and entity.valid and p.can_reach_entity(entity), 'player or target became unavailable')
                assert(b.native('now')<deadline, string.format('setting %s was not confirmed in phase %d; inspect the entity before retrying',commands[index] and commands[index].name or 'open',stage))
            end
            b.player_action=true
            b.observe('input',function()
                if finished then return true end
                validate()
                if unchanged or game.tick<=phaseTick then return false end
                if not opened then
                    if p.opened==entity then opened=true;stage=0
                    elseif p.opened_gui_type~=defines.gui_type.none then
                        local result=b.native('close_gui');if result=='waiting' then return false end
                        assert(result=='submitted',result);phaseTick=game.tick;return false
                    else
                        assert(b.native('aim','',entity.position)=='submitted', 'cannot scope target position')
                        if p.selected~=entity or stage==0 then
                            assert(b.native('point','',entity.position)=='submitted', 'cannot point at entity');stage=1
                        elseif stage==1 then
                            assert(b.native('tap','open-gui',entity.position)=='submitted', 'cannot open entity');stage=2
                        end
                        phaseTick=game.tick;return false
                    end
                end
                assert(p.opened==entity, 'opened entity changed during configuration')
                local command=commands[index]
                if not command then return false end
                if stage==0 and command.read()==command.value then index=index+1;return false end
                local result
                if command.kind=='number' then
                    if stage>0 then return false end
                    result=b.native('setting_number',command.name,command.value)
                elseif command.kind=='section' then
                    if stage>=2 then return false end
                    result=b.native(stage==0 and 'logistic_point' or 'logistic_toggle',command.index,command.count)
                elseif command.kind=='toggle' then
                    if stage>=2 then return false end
                    result=b.native(stage==0 and 'setting_point' or 'setting_toggle',command.name,command.choice)
                else
                    if not operations then operations=entity.get_recipe() and {'recipe_open_point','recipe_open','recipe','recipe_confirm'} or {'recipe','recipe_confirm'} end
                    if not operations[stage+1] then return false end
                    result=b.native(operations[stage+1],command.value)
                end
                if result=='waiting' then return false end
                assert(result=='submitted' or result=='selected',result)
                stage=stage+1;phaseTick=game.tick
                return false
            end,fail)
            b.observe('tick',function()
                if finished then return true end
                validate()
                local command=commands[index]
                local submitted=command and stage>=((command.kind=='toggle' or command.kind=='section') and 2 or 1)
                if opened and submitted and command.read()==command.value then index=index+1;stage=0;operations=nil end
                if unchanged or index>#commands then
                    finish({entity=entity.name,position=entity.position,settings=settings,recipe=settings.recipe,unchanged=unchanged,tick=game.tick},false)
                    return true
                end
                return false
            end,fail)
        end}
    """.trimIndent()
    return submitPlayerTask(task, timeoutMillis)
}

internal val entityConfigurationQuerySource = """
    local settings={}
    if entity.type=='assembling-machine' then
        local recipe,quality=entity.get_recipe()
        settings.recipe=recipe and recipe.name or nil
        settings.recipe_quality=quality and quality.name or nil
    elseif entity.type=='container' or entity.type=='logistic-container' then
        local inventory=entity.get_inventory(defines.inventory.chest)
        if inventory and inventory.supports_bar() then settings.inventory_bar=inventory.get_bar()-1 end
    elseif entity.type=='inserter' then
        settings.filters={}
        for i=1,entity.filter_slot_count do settings.filters[i]=entity.get_filter(i) or {} end
        settings.filter_mode=entity.inserter_filter_mode
        settings.use_filters=entity.use_filters
        settings.stack_size=entity.inserter_stack_size_override
    elseif entity.type=='splitter' then
        settings.input_priority=entity.splitter_input_priority
        settings.output_priority=entity.splitter_output_priority
        settings.splitter_filter=entity.splitter_filter
    end
    if entity.type=='train-stop' then settings.station_name=entity.backer_name;settings.train_limit=entity.trains_limit end
    if entity.type=='space-platform-hub' then settings.request_missing_construction_materials=false end
    local sections=entity.get_logistic_sections()
    if sections then
        settings.logistic_sections={}
        for _,section in pairs(sections.sections) do
            local filters={}
            for index,filter in pairs(section.filters) do
                if filter.value then filters[#filters+1]={index=index,name=filter.value.name,quality=filter.value.quality,
                    comparator=filter.value.comparator,count=filter.min,max_count=filter.max,
                    import_from=filter.import_from and filter.import_from.name or nil,minimum_delivery_count=filter.minimum_delivery_count} end
            end
            settings.logistic_sections[#settings.logistic_sections+1]={index=section.index,manual=section.is_manual,
                active=section.active,group=section.group,multiplier=section.multiplier,filters=filters}
            if section.type==defines.logistic_section_type.request_missing_materials_controlled then settings.request_missing_construction_materials=section.active end
        end
    end
    if entity.type=='rocket-silo' then settings.use_transitional_requests=entity.use_transitional_requests end
    local control=entity.get_control_behavior()
    if control then
        settings.control={}
        for _,field in ipairs{'circuit_condition','circuit_enable_disable','logistic_condition','connect_to_logistic_network','parameters'} do
            local ok,value=pcall(function() return control[field] end)
            if ok then settings.control[field]=value end
        end
    end
    local supported=helpers.json_to_table(${luaQuote(entitySettingTypes.toString())})
    local writable={}
    for name,types in pairs(supported) do
        for _,kind in ipairs(types) do if entity.type==kind then writable[#writable+1]=name;break end end
    end
    table.sort(writable)
    return {tick=game.tick,name=entity.name,position=entity.position,settings=settings,writable_settings=writable}
""".trimIndent()
