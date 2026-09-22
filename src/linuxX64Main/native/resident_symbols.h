#pragma once
#include "debug_image.h"
#include "resident.h"
#include <iterator>

// Semantic entry points are resolved and verified against the target developer
// image.
inline constexpr const char *resident_symbols[] = {
    factorio::app_process,
    factorio::game_update,
    "lua_pcallk",
    factorio::input_phase,
    "close_state(lua_State*)",
    factorio::research_paint,
    factorio::research_destructor,
    factorio::map_update,
    factorio::map_destructor,
    factorio::view_update,
    factorio::view_destructor,
    "CraftingGui::updateCounts()",
    "_ZN4agui6WidgetD2Ev",
    "ControlInputValue::isActive(bool, ControlInputValue::ContinuousAction, "
    "bool, bool, NamedBool<CheckModifiersTag>) const",
    "InventoryGuiSlot::InventoryGuiSlot(RelativeItemStackLocation, GuiContext, "
    "ID<ItemPrototype, unsigned short>, ID<QualityPrototype, unsigned char>, "
    "NamedBool<SubscribeToGameNotificationsTag>)",
    "CharacterView::update(bool)",
    "_ZN13CharacterViewD2Ev",
    "AssemblingMachineGui::logic(double)",
    "SelectListGui<ID<RecipePrototype, unsigned short> "
    ">::update(NamedBool<TriggeredBySearchTag>, "
    "SelectListGui<ID<RecipePrototype, unsigned short> "
    ">::PreserveScrollPosition)",
    "IconButton::IconButton(Sprite const&, std::__cxx11::basic_string<char, "
    "std::char_traits<char>, std::allocator<char> >&&, agui::ButtonStyle "
    "const*, agui::GenericTargetable*, std::function<void ()>)",
    "SDLWindow::pollEvent(Event&)",
    "_ZN9SDLWindowD2Ev",
    "Player::getCursorMapPosition() const",
    "SDL_PushEvent",
    "LuaItemStackEntityTarget::getItemStack()",
    "CharacterController::getStack(RelativeItemStackLocation const&)",
    "InventoryGuiSlot::paintComponent(agui::PaintEvent const&, agui::Point "
    "const&)",
    "EquipmentGridGuiHolder::paintComponent(agui::PaintEvent const&, "
    "agui::Point const&)",
    "luaC_forcestep(lua_State*)",
    "EquipmentGridGuiDrawer::getCursorPosition() const",
    "SurfaceList::logic(double)",
    "NewSpacePlatformGui::requestModalFocus(agui::ModalFocusPriority, bool)",
    "ScheduleGui::update()",
    "agui::Widget::onClick(agui::GenericTargetable*, std::function<void ()>)",
    "LabeledSwitch::onSwitchToggle(agui::GenericTargetable*, "
    "std::function<void ()>)",
    "agui::Widget::setText(std::__cxx11::basic_string<char, "
    "std::char_traits<char>, std::allocator<char> >&&)",
    "agui::ListBox::getItemCount() const",
    "agui::Widget::onToggle(agui::GenericTargetable*, std::function<void ()>)",
    "agui::ToggleButton::onCheckChange(agui::GenericTargetable*, "
    "std::function<void (bool)>)",
    "NumberInputGui::onNumberInputValueChange(agui::GenericTargetable*, "
    "std::function<void ()>)",
    "_ZN14NumberInputGuiD2Ev",
    "agui::ToggleButton::onCheckChange(agui::GenericTargetable*, "
    "std::function<void ()>)",
    "_ZN4agui12ToggleButtonD2Ev",
    "LabeledSwitch::LabeledSwitch(std::__cxx11::basic_string<char, "
    "std::char_traits<char>, std::allocator<char> >, "
    "std::__cxx11::basic_string<char, std::char_traits<char>, "
    "std::allocator<char> >)",
    "_ZN13LabeledSwitchD2Ev",
    "AssemblingMachineSelectRecipeGui::logic(double)",
    "_ZN32AssemblingMachineSelectRecipeGuiD2Ev",
    factorio::app_in_menu,
    factorio::event_dispatch,
    "lua_load",
    "lua_tolstring",
    "lua_settop",
    "lua_pushcclosure",
    "lua_setglobal",
    "SDL_PushEvent",
    "lua_pushlstring",
    "lua_pushnumber",
    "lua_tonumberx",
    factorio::technology_parser,
    factorio::start_research,
    factorio::open_research,
    factorio::find_control,
    factorio::tap_control,
    factorio::position_parser,
    factorio::screen_position,
    "ID<RecipePrototype, unsigned short> LuaHelper::parse<ID<RecipePrototype, "
    "unsigned short> >(lua_State*, int, char const*)",
    "RecipeSlot* agui::Widget::getWidget<RecipeSlot>(std::function<bool "
    "(RecipeSlot*)> const&)",
    "RecipeSlot::getID() const",
    "agui::Widget::getAbsoluteRectangle() const",
    "CraftingGui::search(std::__cxx11::basic_string<char, "
    "std::char_traits<char>, std::allocator<char> > const&)",
    "PlayerInputSource::processCloseGui()",
    "CharacterView::getCraftingSlotRectangle(unsigned int) const",
    "ControlInput::isActive(bool, NamedBool<GuiCheckTag>, bool, "
    "NamedBool<CheckModifiersTag>) const",
    "SelectListGui<ID<RecipePrototype, unsigned short> >::showWithoutGroups()",
    "SelectListGui<ID<RecipePrototype, unsigned short> "
    ">::selectByClicking(ID<RecipePrototype, unsigned short>, bool)",
    "AssemblingMachineGui::AssemblingMachineGui(AssemblingMachine const*, "
    "GuiContext)",
    "SDLWindow::getWindow()",
    "SDL_GetWindowID",
    "PlayerInputSource::processBuild()",
    "PlayerInputSource::processAlternativeBuild()",
    "SDL_SendWindowEvent",
    "SDL_EventState",
    "PlayerInputSource::processRotateEntityCommon(bool)",
    "lua_getlfield",
    "InventoryGuiSlot::getStack() const",
    "AppManager::tryImportBlueprintStringFromText(std::__cxx11::basic_string<"
    "char, std::char_traits<char>, std::allocator<char> > const&)",
    "PlayerInputSource::processQuickBarSelectNextPreviousPage(bool)",
    "Player* LuaHelper::parse<Player*>(lua_State*, int, char const*)",
    "Map::getLocalPlayer()",
    "Scenario::update()",
    "SelectListGui<ID<RecipePrototype, unsigned short> "
    ">::update(NamedBool<TriggeredBySearchTag>, "
    "SelectListGui<ID<RecipePrototype, unsigned short> "
    ">::PreserveScrollPosition)",
    "agui::Widget::keepVerticallyVisibleInScrollPane(int, int)",
    "GuiContext::openChart(SurfaceIndex, MapPosition, StrongTypedef<double, "
    "ZoomTag, PositiveDoubleValidator>) const",
    "SurfaceList::newSpacePlatformButtonClicked()",
    "NewSpacePlatformGui::confirm()",
    "RocketSiloGui::logic(double)",
    "RocketSiloGui::launchRocketToPlatform(unsigned int, bool, "
    "NamedBool<TransportPlayerTag>)",
    "ScheduleGui::prepareStationSelection(agui::Widget&, bool)",
    "StationsList::update()",
    "agui::Widget::getText[abi:cxx11]() const",
    "SpacePlatformScheduleFrame::SpacePlatformScheduleFrame(GuiContext, "
    "SpacePlatform const*)",
    "RemoteViewGui::update()",
    "std::_Function_handler<void (), "
    "RemoteViewGui::RemoteViewGui(GuiContext)::$_2>::_M_invoke(std::_Any_data "
    "const&)",
    "agui::ListBox::getItemAt[abi:cxx11](int) const",
    "agui::Widget::dispatchItemSelect(int, bool)",
    "ScheduleStationGui::ScheduleStationGui(ScheduleGui&, DragPane&, "
    "ScheduleRecordPosition)",
    "LogisticGuiSection::LogisticGuiSection(GuiContext, "
    "CompiledLogisticFilters const&, unsigned char, unsigned char, DragPane*, "
    "std::function<bool (SignalIDBase)>, std::function<Result (SignalFilter)>, "
    "NamedBool<AllowEditingCountTag>, LogisticGuiSection::Init)",
    "TrainGui::TrainGui(Locomotive const*, GuiContext)",
    "NumberInputGui::setDisplayValue(double)",
    "NumberInputGui::onTextEditInternal(agui::Widget*)",
    "agui::ToggleButton::nextCheckState()",
    "AssemblingMachineSelectRecipeGui::confirm()"};
static_assert(std::size(resident_symbols) == FR_FUNCTION_COUNT,
              "Resident symbol catalog must match its function indices");
