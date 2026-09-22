#include "resident.cpp"
#include <cstdio>
#include <vector>

namespace {
void require(bool value, const char *message) {
    if (!value)
        throw std::runtime_error(message);
}
int connect_peer() {
    int pair[2];
    require(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC,
                       0, pair) == 0,
            "Cannot create IPC fixture");
    client = pair[0];
    return pair[1];
}
std::vector<SDL_Event> pointer_events;
bool mouse_inside = false;
int record_event(SDL_Event *event) {
    FrFrame frame{};
    frame.branch = FR_EVENT_PUSH;
    frame.rdi = reinterpret_cast<uintptr_t>(event);
    resident_enter(&frame, nullptr);
    pointer_events.push_back(*event);
    return 1;
}
void *window_handle(void *window) { return window; }
uint32_t window_id(void *) { return 23; }
uint8_t event_state(uint32_t, int) { return SDL_ENABLE; }
void window_event(void *, uint8_t type, int, int) {
    bool inside = type == SDL_WINDOWEVENT_ENTER;
    if (inside == mouse_inside)
        return;
    mouse_inside = inside;
    SDL_Event event{};
    event.type = SDL_WINDOWEVENT;
    event.window.windowID = 23;
    event.window.event = type;
    record_event(&event);
}
void verify_pointer_scope() {
    config.functions[FR_PUSH_EVENT].address =
        reinterpret_cast<uintptr_t>(record_event);
    config.functions[FR_WINDOW_HANDLE].address =
        reinterpret_cast<uintptr_t>(window_handle);
    config.functions[FR_WINDOW_ID].address =
        reinterpret_cast<uintptr_t>(window_id);
    config.functions[FR_SEND_WINDOW_EVENT].address =
        reinterpret_cast<uintptr_t>(window_event);
    config.functions[FR_EVENT_STATE].address =
        reinterpret_cast<uintptr_t>(event_state);
    int window = 0;
    game_window = &window;
    point_pixel({12, 34});
    point_pixel({56, 78});
    require(pointer_events.size() == 3 &&
                pointer_events[0].window.event == SDL_WINDOWEVENT_ENTER,
            "Pointer scope did not enter exactly once");
    require(pointer_events[1].motion.windowID == 23 &&
                pointer_events[1].motion.x == 12 &&
                pointer_events[2].motion.y == 78,
            "Pointer event lost its dynamic window or coordinates");
    release_pointer();
    require(pointer_events.back().window.event == SDL_WINDOWEVENT_LEAVE &&
                !pointer_window,
            "Pointer scope did not restore the original outside state");
    mouse_inside = true;
    point_pixel({0, 0});
    auto events = pointer_events.size();
    set_world(123);
    require(mouse_inside && pointer_events.size() == events && !pointer_window,
            "World teardown did not release the pointer scope");
    mouse_inside = false;
    point_pixel({1, 2});
    FrFrame frame{};
    frame.branch = FR_WINDOW_CLOSE;
    frame.rdi = reinterpret_cast<uintptr_t>(game_window);
    resident_enter(&frame, nullptr);
    require(!pointer_window && !game_window && !mouse_inside,
            "Window destruction left a dangling pointer scope");
}
int first_stack = 0, requested_stack = 0, missing_stack = 0;
void *resolved_stack = &requested_stack;
std::string slot_result;
const char *slot_operation = "slot_point";
const char *slot_string(void *, int index, size_t *size) {
    if (index != 1)
        throw std::runtime_error("Unexpected Lua string argument");
    *size = strlen(slot_operation);
    return slot_operation;
}
void slot_push(void *, const char *value, size_t size) {
    slot_result.assign(value, size);
}
void slot_pop(void *, int index) {
    require(index == -2, "Unexpected Lua stack restoration");
}
int read_stack(void *, int index, const char *field, size_t length) {
    require(index == 2 && std::string(field, length) == "count",
            "Unexpected slot getter");
    FrFrame frame{};
    frame.branch = FR_STACK_ENTITY;
    frame.return_address = 123;
    resident_enter(&frame, nullptr);
    require(frame.return_address == reinterpret_cast<uintptr_t>(resident_after),
            "Stack getter was not observed");
    frame.rax = reinterpret_cast<uintptr_t>(resolved_stack);
    resident_leave(&frame, nullptr);
    require(frame.branch == 123, "Stack getter return address was changed");
    return 0;
}
void *widget_stack(void *widget) { return widget; }
Rectangle widget_rectangle(void *widget) {
    return {widget == &requested_stack ? 40 : 0, 0, 20, 20};
}
void verify_exact_slot() {
    config.functions[FR_TOSTRING].address =
        reinterpret_cast<uintptr_t>(slot_string);
    config.functions[FR_PUSHSTRING].address =
        reinterpret_cast<uintptr_t>(slot_push);
    config.functions[FR_SETTOP].address = reinterpret_cast<uintptr_t>(slot_pop);
    config.functions[FR_GETFIELD].address =
        reinterpret_cast<uintptr_t>(read_stack);
    config.functions[FR_SLOT_STACK].address =
        reinterpret_cast<uintptr_t>(widget_stack);
    config.functions[FR_WIDGET_RECT].address =
        reinterpret_cast<uintptr_t>(widget_rectangle);
    inventory_slots = {&first_stack, &requested_stack};
    input_source = &requested_stack;
    game_window = &requested_stack;
    native_library(nullptr);
    require(slot_result == "submitted" && pointer_events.back().motion.x == 50,
            "Selected another slot instead of the requested empty slot");
    require(!capturing_stack && !captured_stack && !return_count,
            "Stack observation escaped its scope");
    resolved_stack = &missing_stack;
    native_library(nullptr);
    require(slot_result == "waiting",
            "An unavailable slot was guessed from another widget");
    release_pointer();
    input_source = nullptr;
    game_window = nullptr;
    inventory_slots.clear();
}
bool search_cleared = false, crafting_refreshed = false, recipes_shown = false;
void clear_craft_search(void *gui, const std::string &text) {
    require(gui == &requested_stack && text.empty(),
            "Crafting filter was not cleared");
    search_cleared = true;
}
void refresh_crafting(void *gui, bool search, bool preserve_scroll) {
    require(gui == &requested_stack && !search && !preserve_scroll &&
                search_cleared,
            "Crafting GUI refresh lost its receiver or arguments");
    crafting_refreshed = true;
}
void show_recipes(void *gui) {
    require(gui == &requested_stack && crafting_refreshed,
            "Recipes were shown before clearing the old layout");
    recipes_shown = true;
}
void verify_craft_selection() {
    config.functions[FR_CRAFT_SEARCH].address =
        reinterpret_cast<uintptr_t>(clear_craft_search);
    config.functions[FR_CRAFT_REFRESH].address =
        reinterpret_cast<uintptr_t>(refresh_crafting);
    config.functions[FR_CRAFT_UNGROUP].address =
        reinterpret_cast<uintptr_t>(show_recipes);
    input_source = &requested_stack;
    slot_operation = "craft_prepare";
    crafting_gui = nullptr;
    native_library(nullptr);
    require(slot_result == "waiting" && !recipes_shown,
            "Crafting used an unavailable GUI");
    crafting_gui = &requested_stack;
    native_library(nullptr);
    require(slot_result == "selected" && recipes_shown,
            "Crafting failed to expose recipe slots");
    slot_operation = "craft_reset";
    crafting_refreshed = false;
    native_library(nullptr);
    require(slot_result == "restored" && crafting_refreshed,
            "Crafting did not restore its ordinary layout");
    crafting_gui = nullptr;
    input_source = nullptr;
}
uint64_t desired_equipment_position = 0x12345678;
double grid_fraction(void *, int, int *) { return .25; }
uint64_t parse_equipment_position(void *, int index, const char *label) {
    require(index == 4 && std::string(label) == "position",
            "Unexpected equipment cursor argument");
    return desired_equipment_position;
}
void verify_equipment_cursor() {
    config.functions[FR_TONUMBER].address =
        reinterpret_cast<uintptr_t>(grid_fraction);
    config.functions[FR_POSITION_PARSE].address =
        reinterpret_cast<uintptr_t>(parse_equipment_position);
    active = true;
    input_source = game_map = game_window = equipment_grid_gui =
        &requested_stack;
    slot_operation = "grid_point";
    native_library(nullptr);
    require(slot_result == "submitted" && equipment_position_override,
            "Grid pointer scope was not established");
    FrFrame frame{};
    frame.branch = FR_GRID_CURSOR;
    frame.rdi = reinterpret_cast<uintptr_t>(equipment_grid_gui);
    frame.return_address = 789;
    resident_enter(&frame, nullptr);
    require(frame.return_address == reinterpret_cast<uintptr_t>(resident_after),
            "Equipment cursor return was not intercepted");
    frame.rax = 42;
    resident_leave(&frame, nullptr);
    require(frame.rax == desired_equipment_position && frame.branch == 789,
            "Equipment cursor did not preserve the opaque parsed position");
    auto depth = return_count;
    frame.branch = FR_GRID_CURSOR;
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    frame.return_address = 789;
    resident_enter(&frame, nullptr);
    require(return_count == depth && frame.return_address == 789,
            "Equipment cursor affected another GUI");
    release_pointer();
    require(!equipment_position_override,
            "Pointer release retained the equipment override");
    frame.branch = FR_GRID_CURSOR;
    frame.rdi = reinterpret_cast<uintptr_t>(equipment_grid_gui);
    resident_enter(&frame, nullptr);
    require(return_count == depth, "Released equipment cursor was intercepted");
    equipment_position_override = true;
    set_world(world + 1);
    require(!equipment_position_override,
            "World teardown retained the equipment cursor");
    input_source = game_map = game_window = equipment_grid_gui = nullptr;
    active = false;
}
double local_result = -1;
void *local_candidate = nullptr, *local_owner = nullptr;
void *parse_player(void *, int index) {
    require(index == 2, "Unexpected Lua player argument");
    return local_candidate;
}
void *find_local_player(void **first, void **last) {
    require(last == first + 1,
            "Local ownership did not receive one opaque player");
    return *first == local_owner ? *first : nullptr;
}
void push_player_result(void *, double value) { local_result = value; }
bool remote_submitted = false;
double remote_number(void *, int index, int *) {
    return index == 4 ? 17 : 1.25;
}
uint64_t remote_position(void *, int index, const char *) {
    require(index == 3, "Remote view parsed the wrong position argument");
    return 0x123456789abcdef0;
}
void open_remote(void **context, uint32_t surface, uint64_t position,
                 double zoom) {
    require(*context == local_owner && surface == 16 &&
                position == 0x123456789abcdef0 && zoom == 1.25,
            "Remote view lost its local context, opaque position or "
            "floating-point argument");
    remote_submitted = true;
}
void verify_remote_submission() {
    config.functions[FR_PARSE_PLAYER].address =
        reinterpret_cast<uintptr_t>(parse_player);
    config.functions[FR_LOCAL_PLAYER].address =
        reinterpret_cast<uintptr_t>(find_local_player);
    config.functions[FR_TONUMBER].address =
        reinterpret_cast<uintptr_t>(remote_number);
    config.functions[FR_POSITION_PARSE].address =
        reinterpret_cast<uintptr_t>(remote_position);
    config.functions[FR_OPEN_CHART].address =
        reinterpret_cast<uintptr_t>(open_remote);
    slot_operation = "remote_view";
    local_owner = local_candidate = input_source = &requested_stack;
    native_library(nullptr);
    require(remote_submitted && slot_result == "submitted",
            "Remote view was not submitted");
    remote_submitted = false;
    local_candidate = &first_stack;
    native_library(nullptr);
    require(!remote_submitted && slot_result != "submitted",
            "Remote view accepted a foreign player");
    input_source = nullptr;
}
bool platform_opened = false, platform_confirmed = false,
     rocket_submitted = false;
void open_platform(void *receiver) {
    require(receiver == &first_stack,
            "Platform creation lost the real surface list");
    platform_opened = true;
}
bool confirm_platform(void *receiver) {
    require(receiver == &requested_stack,
            "Platform confirmation lost the real dialog");
    platform_confirmed = true;
    return true;
}
double rocket_number(void *, int index, int *) { return index == 2 ? 19 : 1; }
void launch_rocket(void *receiver, uint32_t platform, bool confirmed,
                   bool transport) {
    require(receiver == &missing_stack && platform == 19 && confirmed &&
                transport,
            "Rocket submission lost its receiver or arguments");
    rocket_submitted = true;
}
void verify_space_receivers() {
    active = false;
    set_world(world + 1);
    config.functions[FR_NEW_PLATFORM].address =
        reinterpret_cast<uintptr_t>(open_platform);
    config.functions[FR_CONFIRM_PLATFORM].address =
        reinterpret_cast<uintptr_t>(confirm_platform);
    config.functions[FR_LAUNCH_ROCKET].address =
        reinterpret_cast<uintptr_t>(launch_rocket);
    config.functions[FR_ROCKET_LOGIC] = {1000, 100};
    config.functions[FR_TONUMBER].address =
        reinterpret_cast<uintptr_t>(rocket_number);
    input_source = &first_stack;
    slot_operation = "platform_new";
    native_library(nullptr);
    require(slot_result == "waiting" && !platform_opened,
            "Platform creation guessed a missing GUI");
    FrFrame frame{};
    frame.branch = FR_SURFACE_LIST;
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    resident_enter(&frame, nullptr);
    native_library(nullptr);
    require(slot_result == "submitted" && platform_opened,
            "Platform creation failed");
    frame.branch = FR_PLATFORM_DIALOG;
    frame.rdi = reinterpret_cast<uintptr_t>(&requested_stack);
    resident_enter(&frame, nullptr);
    slot_operation = "platform_confirm";
    native_library(nullptr);
    require(slot_result == "submitted" && platform_confirmed,
            "Platform confirmation failed");
    frame.branch = FR_ASSEMBLING_GUI;
    frame.rdi = reinterpret_cast<uintptr_t>(&missing_stack);
    frame.return_address = 1050;
    resident_enter(&frame, nullptr);
    slot_operation = "rocket_launch";
    native_library(nullptr);
    require(slot_result == "submitted" && rocket_submitted,
            "Rocket launch failed");
    frame.branch = FR_WIDGET_CLOSE;
    resident_enter(&frame, nullptr);
    require(!rocket_gui, "Rocket widget destruction retained a receiver");
    set_world(world + 1);
    require(!platform_dialog && !surface_list,
            "World replacement retained a space GUI");
    input_source = nullptr;
}
const std::string station_caption = "[planet=gleba] Gleba";
const std::string &station_text(void *widget) {
    require(widget == &requested_stack, "Station label queried another widget");
    return station_caption;
}
void prepare_schedule(void *receiver, void *anchor, bool temporary) {
    require(receiver == &first_stack && anchor == receiver && !temporary,
            "Schedule preparation lost its actual receiver");
}
void verify_schedule_receivers() {
    active = false;
    set_world(world + 1);
    config.functions[FR_SCHEDULE_PREPARE].address =
        reinterpret_cast<uintptr_t>(prepare_schedule);
    config.functions[FR_STATIONS_UPDATE] = {3000, 100};
    config.functions[FR_WIDGET_TEXT].address =
        reinterpret_cast<uintptr_t>(station_text);
    input_source = &first_stack;
    slot_operation = "schedule_prepare";
    native_library(nullptr);
    require(slot_result == "waiting",
            "Schedule preparation guessed a missing receiver");
    FrFrame frame{};
    frame.branch = FR_SCHEDULE_GUI;
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    resident_enter(&frame, nullptr);
    native_library(nullptr);
    require(slot_result == "submitted", "Schedule preparation failed");
    frame.branch = FR_WIDGET_CLICK;
    frame.rdi = reinterpret_cast<uintptr_t>(&requested_stack);
    frame.return_address = 2000;
    resident_enter(&frame, nullptr);
    require(station_buttons.empty(),
            "Unrelated click registration became a station");
    frame.branch = FR_WIDGET_CLICK;
    frame.return_address = 3050;
    resident_enter(&frame, nullptr);
    require(station_buttons.size() == 1 &&
                station_buttons.begin()->second == station_caption,
            "Station identity was lost");
    frame.branch = FR_WIDGET_CLOSE;
    resident_enter(&frame, nullptr);
    require(station_buttons.empty(), "Destroyed station widget was retained");
    set_world(world + 1);
    require(!schedule_gui && station_buttons.empty(),
            "World teardown retained a schedule GUI");
    input_source = nullptr;
}
double drop_index(void *, int, int *) { return 1; }
int drop_count(void *receiver) {
    require(receiver == &requested_stack, "Wrong drop list");
    return 1;
}
std::string drop_label(void *receiver, int index) {
    require(receiver == &requested_stack && index == 0,
            "Wrong drop item arguments");
    return "[planet=nauvis] Nauvis";
}
bool drop_selected = false;
void drop_select(void *receiver, int index, bool secondary) {
    require(receiver == &requested_stack && index == 0 && !secondary,
            "Wrong drop selection arguments");
    drop_selected = true;
}
void verify_space_controls() {
    active = false;
    set_world(world + 1);
    input_source = &first_stack;
    config.functions[FR_PLATFORM_FRAME] = {1000, 100};
    config.functions[FR_REMOTE_GUI_UPDATE] = {2000, 100};
    config.functions[FR_DROP_SELECTOR] = {3000, 100};
    config.functions[FR_LIST_COUNT].address =
        reinterpret_cast<uintptr_t>(drop_count);
    config.functions[FR_LIST_ITEM].address =
        reinterpret_cast<uintptr_t>(drop_label);
    config.functions[FR_LIST_SELECT].address =
        reinterpret_cast<uintptr_t>(drop_select);
    config.functions[FR_TONUMBER].address =
        reinterpret_cast<uintptr_t>(drop_index);
    FrFrame frame{};
    frame.rdi = reinterpret_cast<uintptr_t>(&requested_stack);
    for (auto hook : {FR_SWITCH_REGISTER, FR_WIDGET_TEXT_SET, FR_LIST_COUNT}) {
        frame.branch = hook;
        frame.return_address = 900;
        resident_enter(&frame, nullptr);
    }
    require(!platform_switch && !drop_button && !drop_list,
            "Unrelated GUI was captured");
    frame.branch = FR_SWITCH_REGISTER;
    frame.return_address = 1050;
    resident_enter(&frame, nullptr);
    frame.branch = FR_WIDGET_TEXT_SET;
    frame.return_address = 2050;
    resident_enter(&frame, nullptr);
    frame.branch = FR_LIST_COUNT;
    frame.return_address = 3050;
    resident_enter(&frame, nullptr);
    require(platform_switch == &requested_stack &&
                drop_button == &requested_stack &&
                drop_list == &requested_stack,
            "Space receivers were not captured");
    slot_operation = "drop_label";
    native_library(nullptr);
    require(slot_result == "[planet=nauvis] Nauvis",
            "String return ABI lost the destination");
    slot_operation = "drop_choose";
    native_library(nullptr);
    require(drop_selected && slot_result == "submitted",
            "Drop selection was not delivered");
    frame.branch = FR_WIDGET_CLOSE;
    resident_enter(&frame, nullptr);
    require(!platform_switch && !drop_button && !drop_list,
            "Destroyed space control retained");
    platform_switch = drop_button = drop_list = &requested_stack;
    set_world(world + 1);
    require(!platform_switch && !drop_button && !drop_list,
            "Space controls survived world teardown");
    input_source = nullptr;
}
double list_number(void *, int index, int *) { return index == 2 ? 1 : 2; }
Rectangle list_rectangle(void *widget) {
    return {40, widget == &first_stack ? 100 : 20, 30, 20};
}
void list_scroll(void *widget, int y, int height) {
    require(widget == &requested_stack && y == 0 && height == 20,
            "Scrolling selected a different GUI row");
}
void verify_scoped_lists() {
    active = false;
    set_world(world + 1);
    input_source = game_window = &first_stack;
    config.functions[FR_STATION_CREATE] = {4000, 100};
    config.functions[FR_LOGISTIC_SECTION_CREATE] = {5000, 100};
    config.functions[FR_TONUMBER].address =
        reinterpret_cast<uintptr_t>(list_number);
    config.functions[FR_WIDGET_RECT].address =
        reinterpret_cast<uintptr_t>(list_rectangle);
    config.functions[FR_SCROLL_VISIBLE].address =
        reinterpret_cast<uintptr_t>(list_scroll);
    FrFrame frame{};
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    frame.return_address = 100;
    frame.branch = FR_WIDGET_TOGGLE;
    resident_enter(&frame, nullptr);
    frame.branch = FR_CHECK_REGISTER;
    resident_enter(&frame, nullptr);
    require(schedule_go_buttons.empty() && logistic_checks.empty(),
            "Unrelated GUI control was admitted");
    std::function<void()> unrelated = +[] {
        throw std::runtime_error(
            "Callback metadata inspection invoked the callback");
    };
    require(callback_has_type(unrelated, "void (*)()"),
            "Standard callback type metadata was not read");
    frame.branch = FR_WIDGET_CLICK;
    frame.return_address = 4050;
    frame.rdx = reinterpret_cast<uintptr_t>(&unrelated);
    resident_enter(&frame, nullptr);
    require(schedule_remove_buttons.empty(),
            "Unrelated schedule callback became a removal button");
    std::function<void(bool)> checkbox_callback = [](bool) {
        throw std::runtime_error("Capture invoked a checkbox callback");
    };
    for (auto widget : {&first_stack, &requested_stack}) {
        frame.rdi = reinterpret_cast<uintptr_t>(widget);
        frame.branch = FR_WIDGET_TOGGLE;
        frame.return_address = 4050;
        resident_enter(&frame, nullptr);
        frame.rdx = reinterpret_cast<uintptr_t>(&checkbox_callback);
        frame.branch = FR_CHECK_REGISTER;
        frame.return_address = 5050;
        resident_enter(&frame, nullptr);
        schedule_remove_buttons.insert(widget);
    }
    for (auto operation :
         {"schedule_go_point", "schedule_remove_point", "logistic_point"}) {
        slot_operation = operation;
        native_library(nullptr);
        require(slot_result == "submitted" &&
                    pointer_events.back().motion.y == 30,
                "Controls were not ordered by game layout");
    }
    frame.branch = FR_WIDGET_CLOSE;
    resident_enter(&frame, nullptr);
    slot_operation = "schedule_go_point";
    native_library(nullptr);
    require(slot_result == "waiting",
            "A partial GUI list retargeted an action");
    slot_operation = "schedule_remove_point";
    native_library(nullptr);
    require(slot_result == "waiting",
            "A deleted removal button remained selectable");
    set_world(world + 1);
    require(schedule_go_buttons.empty() && schedule_remove_buttons.empty() &&
                logistic_checks.empty(),
            "World teardown retained list controls");
    input_source = game_window = nullptr;
}
double displayed_setting = 0;
unsigned setting_checks = 0;
const char *setting_operation = "setting_number", *setting_name = "stack_size";
void setting_check(void *receiver) {
    require(receiver == &first_stack, "Checkbox used the wrong receiver");
    ++setting_checks;
}
void setting_display(void *receiver, double value) {
    require(receiver == &first_stack, "Numeric edit used the wrong receiver");
    displayed_setting = value;
}
void setting_edit(void *receiver, void *widget) {
    require(receiver == &first_stack && !widget && displayed_setting == 2,
            "Numeric edit did not format the field before dispatch");
}
const char *setting_string(void *, int index, size_t *size) {
    const char *value = index == 1 ? setting_operation : setting_name;
    *size = strlen(value);
    return value;
}
void verify_setting_receivers() {
    set_world(world + 1);
    input_source = &first_stack;
    std::function<void()> callback =
        +[] { throw std::runtime_error("Capture invoked a callback"); };
    auto &control = setting_controls[0];
    auto expected = control.callback_type;
    control.callback_type = "void (*)()";
    FrFrame frame{};
    frame.branch = FR_NUMBER_REGISTER;
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    frame.rdx = reinterpret_cast<uintptr_t>(&callback);
    resident_enter(&frame, nullptr);
    control.callback_type = expected;
    require(control.receiver == &first_stack,
            "Number input receiver was not captured");
    config.functions[FR_TOSTRING].address =
        reinterpret_cast<uintptr_t>(setting_string);
    config.functions[FR_NUMBER_DISPLAY].address =
        reinterpret_cast<uintptr_t>(setting_display);
    config.functions[FR_NUMBER_EDIT].address =
        reinterpret_cast<uintptr_t>(setting_edit);
    native_library(nullptr);
    require(slot_result == "submitted", "Numeric edit was not submitted");
    frame.branch = FR_NUMBER_CLOSE;
    resident_enter(&frame, nullptr);
    native_library(nullptr);
    require(slot_result == "waiting",
            "Destroyed numeric control remained callable");
    auto &check =
        *std::find_if(std::begin(setting_controls), std::end(setting_controls),
                      [](const auto &entry) {
                          return std::string_view(entry.name) == "use_filters";
                      });
    check.receiver = &first_stack;
    setting_name = "use_filters";
    setting_operation = "setting_point";
    config.functions[FR_CHECK_NEXT].address =
        reinterpret_cast<uintptr_t>(setting_check);
    native_library(nullptr);
    require(setting_checks == 0, "Checkbox preparation changed its state");
    setting_operation = "setting_toggle";
    native_library(nullptr);
    require(setting_checks == 1 && slot_result == "submitted",
            "Checkbox did not use its normal state transition");
    frame.branch = FR_CHECK_CLOSE;
    resident_enter(&frame, nullptr);
    native_library(nullptr);
    require(setting_checks == 1 && slot_result == "waiting",
            "Destroyed checkbox was used");
    control.receiver = check.receiver = &first_stack;
    set_world(world + 1);
    require(!control.receiver && !check.receiver,
            "Setting controls survived world teardown");
    config.functions[FR_TOSTRING].address =
        reinterpret_cast<uintptr_t>(slot_string);
    input_source = nullptr;
}
void verify_switch_scope() {
    set_world(world + 1);
    active = true;
    FrFrame frame{};
    frame.branch = FR_LABELED_CREATE;
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    frame.return_address = 123;
    resident_enter(&frame, nullptr);
    require(constructing_switches.size() == 1 &&
                constructing_switches.back() == &first_stack,
            "Switch constructor scope lost its receiver");
    switch_labels[&first_stack] = {&requested_stack, &missing_stack};
    resident_leave(&frame, nullptr);
    require(constructing_switches.empty() && frame.branch == 123,
            "Switch constructor scope escaped its return");
    frame.branch = FR_WIDGET_CLOSE;
    frame.rdi = reinterpret_cast<uintptr_t>(&requested_stack);
    resident_enter(&frame, nullptr);
    require(!switch_labels[&first_stack][0],
            "Destroyed switch label remained callable");
    frame.branch = FR_LABELED_CLOSE;
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    resident_enter(&frame, nullptr);
    require(switch_labels.empty(), "Destroyed switch retained its labels");
    active = false;
}
bool confirm_recipe(void *receiver) {
    require(receiver == &first_stack,
            "Recipe confirmation lost the real GUI receiver");
    return true;
}
void verify_recipe_confirmation() {
    active = false;
    set_world(world + 1);
    input_source = &first_stack;
    config.functions[FR_RECIPE_CONFIRM].address =
        reinterpret_cast<uintptr_t>(confirm_recipe);
    slot_operation = "recipe_confirm";
    FrFrame frame{};
    frame.branch = FR_RECIPE_GUI;
    frame.rdi = reinterpret_cast<uintptr_t>(&first_stack);
    resident_enter(&frame, nullptr);
    native_library(nullptr);
    require(slot_result == "submitted",
            "Recipe confirmation was not submitted");
    frame.branch = FR_RECIPE_CLOSE;
    resident_enter(&frame, nullptr);
    native_library(nullptr);
    require(slot_result == "waiting", "Destroyed recipe selector was reused");
    recipe_gui = &first_stack;
    set_world(world + 1);
    require(!recipe_gui, "Recipe selector survived world teardown");
    input_source = nullptr;
}
void verify_local_identity() {
    config.functions[FR_PARSE_PLAYER].address =
        reinterpret_cast<uintptr_t>(parse_player);
    config.functions[FR_LOCAL_PLAYER].address =
        reinterpret_cast<uintptr_t>(find_local_player);
    config.functions[FR_PUSHNUMBER].address =
        reinterpret_cast<uintptr_t>(push_player_result);
    slot_operation = "is_local_player";
    local_owner = &requested_stack;
    for (auto candidate : {&first_stack, &requested_stack, &missing_stack}) {
        local_candidate = candidate;
        native_library(nullptr);
        require(local_result == (candidate == local_owner ? 1 : 0),
                "Another player was identified as the local client");
    }
    local_owner = nullptr;
    native_library(nullptr);
    require(local_result == 0,
            "A client without a local owner selected a player");
}
FrId request_id(const char *value) {
    FrId id{};
    require(strlen(value) < sizeof id.value, "Fixture ID too long");
    strcpy(id.value, value);
    return id;
}
void send_result(const char *id) { reply(request_id(id), "done", 4); }
void expect_result(int peer, const char *id) {
    char packet[sizeof(FrResponse) + 64];
    auto count = recv(peer, packet, sizeof packet, MSG_DONTWAIT);
    require(count >= static_cast<ssize_t>(sizeof(FrResponse)),
            "Completion was not delivered");
    FrResponse response{};
    memcpy(&response, packet, sizeof response);
    require(response.version == FR_VERSION && !strcmp(response.id.value, id) &&
                !response.failed,
            "Completion ID was changed or filtered");
    require(count == static_cast<ssize_t>(sizeof response + response.length),
            "Malformed completion packet");
    require(std::string(packet + sizeof response, response.length) == "done",
            "Completion body changed");
}
void verify_observer_lifecycle(int peer) {
    unsigned changes = 0;
    auto request = [&](FrOperation operation, bool failed = false,
                       bool stale = false) {
        FrRequest request{};
        request.version = FR_VERSION;
        request.operation = operation;
        request.id = request_id("lifecycle");
        request.expected_generation = generation + (stale ? 1 : 0);
        require(send(peer, &request, sizeof request, 0) == sizeof request,
                "Cannot send lifecycle request");
        pump();
        char packet[sizeof(FrResponse) + 512];
        FrResponse response{};
        do {
            auto count = recv(peer, packet, sizeof packet, 0);
            require(count >= static_cast<ssize_t>(sizeof(FrResponse)),
                    "No lifecycle response");
            memcpy(&response, packet, sizeof response);
            if (response.kind == FR_WORLD_CHANGED)
                ++changes;
        } while (response.kind == FR_WORLD_CHANGED);
        require((response.failed != 0) == failed,
                "Unexpected lifecycle result");
        FrStatus status{};
        if (!failed) {
            require(response.length == sizeof status,
                    "Invalid lifecycle status");
            memcpy(&status, packet + sizeof response, sizeof status);
        }
        return status;
    };
    set_world(456);
    factorio_resident_activate_v1(FR_OBSERVER_HOOK_COUNT);
    main_menu = false;
    auto observed = request(FR_STATUS);
    require(observed.in_game && !observed.ready && !observed.binding_requested,
            "Observation bound the world");
    bind(nullptr); // Disabled binding must not even inspect a Lua VM.
    request(FR_BIND_WORLD, true);
    factorio_resident_activate_v1(FR_HOOK_COUNT);
    main_menu = true;
    request(FR_BIND_WORLD, true);
    main_menu = false;
    request(FR_BIND_WORLD, true, true);
    require(request(FR_BIND_WORLD).binding_requested,
            "World binding was not requested");
    set_world(789);
    require(!request(FR_STATUS).binding_requested,
            "World switch retained a binding request");
    require(request(FR_BIND_WORLD).binding_requested,
            "New world cannot be attached");
    world_lua = reinterpret_cast<void *>(123);
    auto before = generation;
    FrFrame close{};
    close.branch = FR_CLOSE;
    close.rdi = reinterpret_cast<uintptr_t>(world_lua);
    resident_enter(&close, nullptr);
    require(!world_lua && !binding_requested && generation > before,
            "Lua destruction retained world readiness");
    request(FR_STATUS);
    require(changes >= 3, "World lifecycle changes were not notified");
}
void verify_idle_bootstrap_gate() {
    set_world(123);
    active = true;
    config.functions[FR_SCENARIO_UPDATE] = {1000, 100};
    FrFrame frame{};
    frame.branch = FR_GC;
    frame.rdi = 456;
    frame.return_address = 1050;
    auto depth = return_count;
    resident_enter(&frame, nullptr);
    require(return_count == depth,
            "Status-only observation intercepted Lua maintenance");
    binding_requested = true;
    frame.branch = FR_GC;
    frame.return_address = 2000;
    resident_enter(&frame, nullptr);
    require(return_count == depth, "Unrelated Lua maintenance was intercepted");
    frame.branch = FR_GC;
    frame.return_address = 1050;
    resident_enter(&frame, nullptr);
    require(return_count == depth + 1 && returns[depth].lua == 456 &&
                returns[depth].hook == FR_GC,
            "Scenario maintenance did not preserve the actual Lua receiver");
    return_count = depth; // The synthetic receiver is never executed.
    binding_requested = false;
}
} // namespace
int main() {
    int peer = -1;
    try {
        verify_pointer_scope();
        verify_exact_slot();
        verify_craft_selection();
        verify_equipment_cursor();
        verify_local_identity();
        verify_remote_submission();
        verify_space_receivers();
        verify_schedule_receivers();
        verify_space_controls();
        verify_scoped_lists();
        verify_setting_receivers();
        verify_switch_scope();
        verify_recipe_confirmation();
        verify_idle_bootstrap_gate();
        peer = connect_peer();
        verify_observer_lifecycle(peer);
        disconnect();
        close(peer);
        send_result("old-disconnected-id");
        peer = connect_peer();
        char packet[64];
        require(recv(peer, packet, sizeof packet, MSG_DONTWAIT) == -1 &&
                    errno == EAGAIN,
                "An old result was replayed");

        // The game does not know whether the new MCP recognizes this ID.
        send_result("old-pending-id");
        expect_result(peer, "old-pending-id");

        // Multiple completions can be emitted consecutively, without another
        // tick.
        send_result("A");
        send_result("B");
        expect_result(peer, "A");
        expect_result(peer, "B");

        // A peer disappearing during delivery does not block the game.
        close(peer);
        peer = -1;
        for (unsigned i = 0; i < FR_QUEUE_CAPACITY; ++i)
            send_result("disconnected");
        require(client == -1, "Broken IPC endpoint was retained");
        return 0;
    } catch (const std::exception &error) {
        if (peer >= 0)
            close(peer);
        disconnect();
        fprintf(stderr, "%s\n", error.what());
        return 1;
    }
}
