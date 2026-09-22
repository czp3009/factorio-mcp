#pragma once
#include <stdint.h>
#include <stddef.h>

// Private, versioned ABI between this executable and its embedded resident.
// Game addresses always come from the developer ELF/DWARF, never this protocol.
enum { FR_VERSION = 20, FR_MAX_REQUEST = 48000, FR_MAX_RESPONSE = 200000, FR_QUEUE_CAPACITY = 64 };
enum FrHook { FR_APP, FR_UPDATE, FR_PCALL, FR_INPUT, FR_CLOSE, FR_TECH_PAINT, FR_TECH_CLOSE, FR_MAP, FR_MAP_CLOSE, FR_VIEW, FR_VIEW_CLOSE, FR_CRAFT_GUI, FR_WIDGET_CLOSE, FR_CONTROL, FR_SLOT_CREATE, FR_CHARACTER_VIEW, FR_CHARACTER_CLOSE, FR_ASSEMBLING_GUI, FR_RECIPE_LIST, FR_ICON_CREATE, FR_WINDOW_POLL, FR_WINDOW_CLOSE, FR_CURSOR_PLAYER, FR_EVENT_PUSH, FR_STACK_ENTITY, FR_STACK_CONTROLLER, FR_SLOT_PAINT, FR_GRID_PAINT, FR_GC, FR_GRID_CURSOR, FR_SURFACE_LIST, FR_PLATFORM_DIALOG, FR_SCHEDULE_GUI, FR_WIDGET_CLICK, FR_SWITCH_REGISTER, FR_WIDGET_TEXT_SET, FR_LIST_COUNT, FR_WIDGET_TOGGLE, FR_CHECK_REGISTER, FR_NUMBER_REGISTER, FR_NUMBER_CLOSE, FR_CHECK_VOID_REGISTER, FR_CHECK_CLOSE, FR_LABELED_CREATE, FR_LABELED_CLOSE, FR_RECIPE_GUI, FR_RECIPE_CLOSE, FR_HOOK_COUNT };
enum FrFunction {
    FR_IS_MENU = FR_HOOK_COUNT, FR_EVENT, FR_LOAD, FR_TOSTRING, FR_SETTOP,
    FR_PUSH_CLOSURE, FR_SETGLOBAL, FR_PUSH_EVENT,
    FR_PUSHSTRING, FR_PUSHNUMBER, FR_TONUMBER, FR_TECH_PARSE, FR_RESEARCH, FR_RESEARCH_TOGGLE, FR_FIND_CONTROL, FR_TAP_CONTROL, FR_POSITION_PARSE, FR_SCREEN_POSITION, FR_RECIPE_PARSE, FR_RECIPE_FIND, FR_RECIPE_ID, FR_WIDGET_RECT, FR_CRAFT_SEARCH, FR_CLOSE_GUI, FR_CRAFT_QUEUE_RECT, FR_CONTROL_READ, FR_CRAFT_UNGROUP, FR_SELECT_RECIPE, FR_ASSEMBLING_CREATE, FR_WINDOW_HANDLE, FR_WINDOW_ID, FR_BUILD, FR_GHOST_BUILD, FR_SEND_WINDOW_EVENT, FR_EVENT_STATE, FR_ROTATE, FR_GETFIELD, FR_SLOT_STACK, FR_BLUEPRINT_IMPORT, FR_QUICKBAR_PAGE, FR_PARSE_PLAYER, FR_LOCAL_PLAYER, FR_SCENARIO_UPDATE, FR_CRAFT_REFRESH, FR_SCROLL_VISIBLE, FR_OPEN_CHART, FR_NEW_PLATFORM, FR_CONFIRM_PLATFORM, FR_ROCKET_LOGIC, FR_LAUNCH_ROCKET, FR_SCHEDULE_PREPARE, FR_STATIONS_UPDATE, FR_WIDGET_TEXT, FR_PLATFORM_FRAME, FR_REMOTE_GUI_UPDATE, FR_DROP_SELECTOR, FR_LIST_ITEM, FR_LIST_SELECT, FR_STATION_CREATE, FR_LOGISTIC_SECTION_CREATE, FR_TRAIN_CREATE, FR_NUMBER_DISPLAY, FR_NUMBER_EDIT, FR_CHECK_NEXT, FR_RECIPE_CONFIRM, FR_FUNCTION_COUNT
};
enum FrOperation { FR_STATUS, FR_LUA, FR_BIND_WORLD };
enum FrMessageKind { FR_COMPLETION, FR_WORLD_CHANGED };
struct FrFunctionInfo { uintptr_t address; size_t size; };
struct FrConfig {
    uint32_t version;
    struct FrFunctionInfo functions[FR_FUNCTION_COUNT];
    const char *script;
    size_t script_length;
};
struct FrPatch {
    uintptr_t address, trampoline;
    uint32_t length;
    uint64_t instruction_starts;
    unsigned char original[64], replacement[64];
};
struct FrDescriptor {
    uint32_t version, count;
    uint32_t installed, reserved;
    uintptr_t activate;
    struct FrPatch patches[FR_HOOK_COUNT];
    char error[512];
};
struct FrId { char value[37]; };
struct FrRequest {
    uint32_t version, operation;
    // Opaque MCP-generated identifier; never interpreted by the game queue.
    struct FrId id;
    int32_t arguments[4];
    uint32_t length, timeout_ms;
    uint64_t expected_generation;
};
struct FrResponse { uint32_t version, failed; struct FrId id; uint32_t length, kind; };
enum { FR_OBSERVER_HOOK_COUNT = FR_UPDATE + 1 };
struct FrStatus { uint64_t instance, generation, descriptor; uint32_t in_game, ready, main_menu, installed, binding_requested, reserved; };

#ifdef __cplusplus
#include <string>
#include <cstdio>
#include <unistd.h>
inline std::string resident_socket_name(int pid) {
    char name[80];
    snprintf(name, sizeof name, "factorio-mcp-v1-%u-%d", getuid(), pid);
    return name;
}
#endif
