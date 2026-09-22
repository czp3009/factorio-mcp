#define _GNU_SOURCE 1
#include "resident_hooks.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/random.h>
#include <unistd.h>
#include <cpuid.h>
#include <cstring>
#include <cerrno>
#include <string>
#include <stdexcept>
#include <chrono>
#include <string_view>
#include <functional>
#include <cxxabi.h>
#include <cstdlib>
#include <set>
#include <mutex>
#include <cmath>
#include <map>
#include <algorithm>
#include <vector>
#include <array>
#include <SDL_events.h>

extern "C" { __attribute__((visibility("hidden"))) uint64_t resident_xsave_mask = 0; }
namespace {
FrConfig config{};
FrDescriptor descriptor{};
std::string bootstrap;
bool active = false;
bool main_menu = false;
bool binding_requested = false;
uintptr_t world = 0;
void *world_lua = nullptr;
void *app_manager = nullptr;
void *equipment_grid_gui = nullptr;
uint64_t equipment_position=0;
bool equipment_position_override=false;
void *technology_gui = nullptr;
void *recipe_gui = nullptr;
void *surface_list = nullptr, *platform_dialog = nullptr, *rocket_gui = nullptr;
void *schedule_gui = nullptr, *platform_switch = nullptr, *drop_button = nullptr, *drop_list = nullptr;
std::map<void *,std::string> station_buttons;
std::set<void *> schedule_go_buttons, schedule_remove_buttons, logistic_checks;

template<class Signature>
bool callback_has_type(const std::function<Signature> &callback, std::string_view expected) {
    int status=0;
    char *name=abi::__cxa_demangle(callback.target_type().name(),nullptr,nullptr,&status);
    bool matches=status==0 && name && expected==name;
    std::free(name);
    return matches;
}
// Match real listener types, retaining only their game-owned control receivers.
struct SettingControl { const char *name; const char *callback_type; FrHook registration; void *receiver=nullptr; };
SettingControl setting_controls[] = {
    {"stack_size", "InserterGui::addStackSizeControl()::$_1", FR_NUMBER_REGISTER},
    {"stack_size_enabled", "InserterGui::addStackSizeControl()::$_0", FR_CHECK_VOID_REGISTER},
    {"train_limit", "TrainStopGui::TrainStopGui(TrainStop const*, GuiContext)::$_2", FR_NUMBER_REGISTER},
    {"train_limit_enabled", "TrainStopGui::TrainStopGui(TrainStop const*, GuiContext)::$_1", FR_CHECK_REGISTER},
    {"filter_mode", "InserterGui::addFilter()::$_1", FR_SWITCH_REGISTER},
    {"input_priority_enabled", "SplitterGui::SplitterGui(SplitterBase const*, GuiContext)::$_3", FR_CHECK_VOID_REGISTER},
    {"output_priority_enabled", "SplitterGui::SplitterGui(SplitterBase const*, GuiContext)::$_4", FR_CHECK_REGISTER},
    {"input_priority", "SplitterGui::SplitterGui(SplitterBase const*, GuiContext)::$_5", FR_SWITCH_REGISTER},
    {"output_priority", "SplitterGui::SplitterGui(SplitterBase const*, GuiContext)::$_6", FR_SWITCH_REGISTER},
    {"use_filters", "InserterGui::addFilter()::$_0", FR_CHECK_REGISTER},
    {"request_missing_construction_materials", "SpacePlatformHubGui::SpacePlatformHubGui(SpacePlatformHub const&, GuiContext)::$_2", FR_CHECK_REGISTER}
};
std::map<void *,std::array<void *,2>> switch_labels;
thread_local std::vector<void *> constructing_switches;
void *game_window = nullptr;
void *pointer_window = nullptr;
bool observing_pointer_entry = false, pointer_added_entry = false;
void release_pointer();
void *game_map = nullptr, *game_view = nullptr, *crafting_gui = nullptr, *character_view = nullptr, *assembling_gui = nullptr, *recipe_list = nullptr, *recipe_button = nullptr;
std::mutex slots_mutex;
std::set<void *> inventory_slots;
struct Pixel { int32_t x, y; };
struct Rectangle { int32_t x, y, width, height; };
thread_local void *input_source = nullptr;
uint64_t generation = 0, instance = 0;
int listener = -1, client = -1;
struct Return { uintptr_t address, lua; uint64_t generation; unsigned hook; const char *control; };
thread_local bool in_input = false;
thread_local bool position_override = false;
thread_local uintptr_t input_position = 0;
thread_local FrFrame *control_frame = nullptr;
thread_local const char *control_name = nullptr;
struct Control { const char *name; void *address; std::set<void *> values; };
thread_local Control *collect_control = nullptr;
Control controls[] = {{"build",nullptr,{}},{"mine",nullptr,{}},{"pick-items",nullptr,{}},{"shoot-selected",nullptr,{}},{"shoot-enemy",nullptr,{}},{"drop-cursor",nullptr,{}}};
thread_local Return returns[256];
thread_local size_t return_count = 0;
thread_local bool executing = false;
thread_local bool capturing_stack = false;
thread_local void *captured_stack = nullptr;
struct StackCapture {
    StackCapture() { captured_stack=nullptr; capturing_stack=true; }
    ~StackCapture() { capturing_stack=false; captured_stack=nullptr; }
};
template<class F> F function(FrFunction index) { return reinterpret_cast<F>(config.functions[index].address); }
template<class F> F original(FrHook index) { return reinterpret_cast<F>(descriptor.patches[index].trampoline); }

void disconnect() { if (client >= 0) close(client); client = -1; }
void reply(const FrId &id, const char *body, size_t size, bool failed = false, FrMessageKind kind = FR_COMPLETION) {
    if (client < 0) return;
    FrResponse header{FR_VERSION, failed ? 1u : 0u, id, static_cast<uint32_t>(size), static_cast<uint32_t>(kind)};
    iovec vectors[] = {{&header, sizeof header}, {const_cast<char *>(body), size}};
    msghdr message{}; message.msg_iov = vectors; message.msg_iovlen = 2;
    // The transport owns no tasks, result history, or MCP session generations.
    if (sendmsg(client, &message, MSG_DONTWAIT | MSG_NOSIGNAL) != static_cast<ssize_t>(sizeof header + size)) disconnect();
}
void reply_error(const FrId &id, const std::string &message) { reply(id, message.data(), message.size(), true); }
void set_world(uintptr_t next) {
    if (world == next) return;
    release_pointer();
    world = next;
    world_lua = nullptr;
    binding_requested = false;
    technology_gui = nullptr;recipe_gui=nullptr;
    for (auto &control : setting_controls) control.receiver=nullptr;
    switch_labels.clear();constructing_switches.clear();
    surface_list = platform_dialog = rocket_gui = nullptr;
    schedule_gui=nullptr;station_buttons.clear();
    platform_switch=drop_button=drop_list=nullptr;
    schedule_go_buttons.clear();schedule_remove_buttons.clear();logistic_checks.clear();
    equipment_grid_gui = nullptr;
    equipment_position_override=false;
    game_map = game_view = crafting_gui = character_view = assembling_gui = recipe_list = recipe_button = nullptr;
    { std::lock_guard<std::mutex> lock(slots_mutex); inventory_slots.clear(); }
    ++generation;
    reply(FrId{},nullptr,0,false,FR_WORLD_CHANGED);
}
void push_string(void *state, const char *text, size_t size) {
    function<void(*)(void *, const char *, size_t)>(FR_PUSHSTRING)(state, text, size);
}
const char *string_value(void *state, int index, size_t &size) {
    return function<const char *(*)(void *, int, size_t *)>(FR_TOSTRING)(state, index, &size);
}
void pop(void *state) { function<void(*)(void *, int)>(FR_SETTOP)(state, -2); }
void protected_call(void *state, int arguments, int results) {
    if (!original<int(*)(void *, int, int, int, intptr_t, void *)>(FR_PCALL)(state, arguments, results, 0, 0, nullptr)) return;
    size_t size = 0;
    auto text = string_value(state, -1, size);
    std::string error = text ? std::string(text, size) : "Lua bridge call failed";
    pop(state);
    throw std::runtime_error(error);
}
struct Reader { const char *text; size_t size; };
const char *read_lua(void *, void *userdata, size_t *size) {
    auto &reader = *static_cast<Reader *>(userdata); *size = reader.size; reader.size = 0; return *size ? reader.text : nullptr;
}
std::string lua(void *state, const std::string &source) {
    Reader reader{source.data(), source.size()};
    int error = function<int(*)(void *, void *, void *, const char *, const char *)>(FR_LOAD)(
        state, reinterpret_cast<void *>(read_lua), &reader, "=factorio-mcp", "t");
    if (!error) protected_call(state, 0, 1);
    size_t size = 0;
    auto text = string_value(state, -1, size);
    std::string value = text && size <= FR_MAX_RESPONSE ? std::string(text, size) : "Lua result must be a bounded string";
    pop(state);
    if (error || !text || size > FR_MAX_RESPONSE) throw std::runtime_error(value);
    return value;
}
int send_result(void *state) noexcept {
    size_t id_size = 0, size = 0;
    auto id_text = string_value(state, 1, id_size);
    auto text = string_value(state, 2, size);
    if (!id_text || !id_size || id_size >= sizeof(FrId::value) || !text || size > FR_MAX_RESPONSE) return 0;
    FrId id{}; memcpy(id.value, id_text, id_size);
    bool failed = function<double(*)(void *, int, int *)>(FR_TONUMBER)(state, 3, nullptr) != 0;
    reply(id, text, size, failed);
    return 0;
}
void release_pointer() {
    equipment_position_override=false;
    if (!pointer_window) return;
    if (pointer_added_entry) function<void(*)(void *,uint8_t,int,int)>(FR_SEND_WINDOW_EVENT)(pointer_window,SDL_WINDOWEVENT_LEAVE,0,0);
    pointer_window=nullptr;
    pointer_added_entry=false;
}
void point_pixel(Pixel pixel) {
    if (!game_window) throw std::runtime_error("The game window event context is unavailable");
    auto window=function<void *(*)(void *)>(FR_WINDOW_HANDLE)(game_window);
    auto window_id=function<uint32_t(*)(void *)>(FR_WINDOW_ID)(window);
    if (!window_id) throw std::runtime_error("The game window event target is unavailable");
    if (!pointer_window) {
        if (function<uint8_t(*)(uint32_t,int)>(FR_EVENT_STATE)(SDL_WINDOWEVENT,SDL_QUERY)!=SDL_ENABLE)
            throw std::runtime_error("Game window events are disabled");
        pointer_window=window;
        pointer_added_entry=false;
        // SDL queues an enter event only for a transition. Observe that event to own exactly
        // the transition we created, then restore it at completion or world teardown.
        observing_pointer_entry=true;
        function<void(*)(void *,uint8_t,int,int)>(FR_SEND_WINDOW_EVENT)(window,SDL_WINDOWEVENT_ENTER,0,0);
        observing_pointer_entry=false;
    }
    SDL_Event event{}; event.type=SDL_MOUSEMOTION; event.motion.windowID=window_id; event.motion.x=pixel.x; event.motion.y=pixel.y;
    if (function<int(*)(SDL_Event *)>(FR_PUSH_EVENT)(&event)!=1) throw std::runtime_error("Game pointer event was rejected");
}
void tap_pixel(const std::string_view &name, Pixel pixel) {
    auto control=function<void *(*)(std::string_view)>(FR_FIND_CONTROL)(name);
    if (!control) throw std::runtime_error("Unknown game control");
    function<void(*)(void *, void *, Pixel, bool)>(FR_TAP_CONTROL)(game_map,control,pixel,true);
}
int native_library(void *state) {
    size_t size = 0;
    auto text = string_value(state, 1, size);
    std::string operation = text ? std::string(text, size) : "";
    if (operation == "now") {
        auto time = std::chrono::steady_clock::now().time_since_epoch();
        function<void(*)(void *, double)>(FR_PUSHNUMBER)(state, std::chrono::duration<double, std::milli>(time).count());
        return 1;
    }
    std::string result;
    try {
        if (operation == "is_local_player") {
            // The game's Lua parser owns LuaPlayer validation and object extraction.
            auto player=function<void *(*)(void *,int)>(FR_PARSE_PLAYER)(state,2);
            // Verified optimized ABI: Map::getLocalPlayer receives a Player* range,
            // not a Map receiver. Ask the game about a single candidate; read no fields.
            auto local=player ? function<void *(*)(void **,void **)>(FR_LOCAL_PLAYER)(&player,&player+1) : nullptr;
            function<void(*)(void *,double)>(FR_PUSHNUMBER)(state,local==player && player ? 1 : 0);
            return 1;
        }
        if (operation == "pointer_end") { release_pointer(); result="released"; }
        else if (operation == "control_name") result = control_name ? control_name : "";
        else if (operation == "control_value") {
            if (!control_frame) throw std::runtime_error("Control override requires the scoped input callback");
            auto value = function<double(*)(void *, int, int *)>(FR_TONUMBER)(state, 2, nullptr);
            control_frame->rax = (control_frame->rax & ~uintptr_t(0xff)) | (value != 0 ? 1 : 0);
            result = "applied";
        }
        else if (!input_source) result = "The native action requires the game input phase";
        else if (operation=="setting_number" || operation=="setting_point" || operation=="setting_toggle") {
            size_t size=0;
            auto name=string_value(state,2,size);
            auto control=std::find_if(std::begin(setting_controls),std::end(setting_controls),[&](const auto &entry) {
                return name && std::string_view(name,size)==entry.name;
            });
            if (control==std::end(setting_controls)) throw std::runtime_error("Unknown entity setting control");
            if (!control->receiver) result="waiting";
            else if (operation=="setting_number") {
                if (control->registration!=FR_NUMBER_REGISTER) throw std::runtime_error("Setting is not a numeric input");
                auto value=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,3,nullptr);
                if (!std::isfinite(value)) throw std::runtime_error("Setting value must be finite");
                // Edit the real field, then let its normal listener submit the network action.
                function<void(*)(void *,double)>(FR_NUMBER_DISPLAY)(control->receiver,value);
                function<void(*)(void *,void *)>(FR_NUMBER_EDIT)(control->receiver,nullptr);
                result="submitted";
            } else if (control->registration==FR_CHECK_REGISTER || control->registration==FR_CHECK_VOID_REGISTER) {
                if (operation=="setting_toggle") function<void(*)(void *)>(FR_CHECK_NEXT)(control->receiver);
                result="submitted";
            } else {
                if (control->registration==FR_NUMBER_REGISTER) throw std::runtime_error("Setting is not a toggle");
                size_t choice_size=0;
                auto choice=string_value(state,3,choice_size);
                if (!choice || (std::string_view(choice,choice_size)!="left" && std::string_view(choice,choice_size)!="right"))
                    throw std::runtime_error("Switch selection requires left or right");
                auto labels=switch_labels.find(control->receiver);
                auto widget=labels==switch_labels.end() ? nullptr : labels->second[std::string_view(choice,choice_size)=="right" ? 1 : 0];
                if (!widget) throw std::runtime_error("Switch labels are unavailable; reopen the entity");
                auto rect=function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(widget);
                if (rect.width<=0 || rect.height<=0) throw std::runtime_error("Setting control is not visible");
                Pixel pixel{rect.x+rect.width/2,rect.y+rect.height/2};
                if (operation=="setting_point") point_pixel(pixel);
                else tap_pixel("pick-item",pixel);
                result="submitted";
            }
        }
        else if (operation=="platform_switch_point" || operation=="platform_switch_toggle" || operation=="drop_point" || operation=="drop_open") {
            auto widget=(operation=="platform_switch_point" || operation=="platform_switch_toggle") ? platform_switch : drop_button;
            if (!widget) result="waiting";
            else {
                auto rect=function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(widget);
                if (rect.width<=0 || rect.height<=0) throw std::runtime_error("Game control is not visible");
                Pixel pixel{rect.x+rect.width/2,rect.y+rect.height/2};
                if ((operation=="platform_switch_point" || operation=="drop_point")) point_pixel(pixel);
                else tap_pixel("pick-item",pixel);
                result="submitted";
            }
        }
        else if (operation=="drop_count") {
            auto count=drop_list ? function<int(*)(void *)>(static_cast<FrFunction>(FR_LIST_COUNT))(drop_list) : 0;
            function<void(*)(void *,double)>(FR_PUSHNUMBER)(state,count);
            return 1;
        }
        else if (operation=="drop_label" || operation=="drop_choose") {
            auto index=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,2,nullptr);
            auto count=drop_list ? function<int(*)(void *)>(static_cast<FrFunction>(FR_LIST_COUNT))(drop_list) : 0;
            if (!std::isfinite(index) || index<1 || index>count || std::floor(index)!=index)
                throw std::runtime_error("Drop destination is unavailable");
            if (operation=="drop_label") result=function<std::string(*)(void *,int)>(FR_LIST_ITEM)(drop_list,static_cast<int>(index)-1);
            else {
                function<void(*)(void *,int,bool)>(FR_LIST_SELECT)(drop_list,static_cast<int>(index)-1,false);
                result="submitted";
            }
        }
        else if (operation=="schedule_go_point" || operation=="schedule_go" || operation=="schedule_remove_point" || operation=="schedule_remove" || operation=="logistic_point" || operation=="logistic_toggle") {
            bool schedule=operation=="schedule_go_point" || operation=="schedule_go";
            bool removing=operation=="schedule_remove_point" || operation=="schedule_remove";
            auto &widgets=schedule ? schedule_go_buttons : removing ? schedule_remove_buttons : logistic_checks;
            auto index=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,2,nullptr);
            auto expected=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,3,nullptr);
            if (!std::isfinite(index) || !std::isfinite(expected) || index<1 || index>expected || std::floor(index)!=index)
                throw std::runtime_error("Invalid game control index");
            if (widgets.size()!=expected) result="waiting";
            else {
                std::vector<std::pair<void *,Rectangle>> rows;
                for (auto widget : widgets) rows.push_back({widget,function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(widget)});
                std::sort(rows.begin(),rows.end(),[](const auto &left,const auto &right) { return left.second.y<right.second.y; });
                for (size_t i=1;i<rows.size();++i)
                    if (rows[i-1].second.y==rows[i].second.y) throw std::runtime_error("Game controls do not form an unambiguous list");
                auto widget=rows[static_cast<size_t>(index)-1].first;
                auto rect=rows[static_cast<size_t>(index)-1].second;
                if (rect.width<=0 || rect.height<=0) throw std::runtime_error("Game control is not visible");
                if (operation=="schedule_go_point" || operation=="schedule_remove_point" || operation=="logistic_point") {
                    function<void(*)(void *,int,int)>(FR_SCROLL_VISIBLE)(widget,0,rect.height);
                    rect=function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(widget);
                    point_pixel({rect.x+rect.width/2,rect.y+rect.height/2});
                } else tap_pixel("pick-item",{rect.x+rect.width/2,rect.y+rect.height/2});
                result="submitted";
            }
        }
        else if (operation == "schedule_prepare") {
            if (!schedule_gui) result="waiting";
            else {
                station_buttons.clear();
                function<void(*)(void *,void *,bool)>(FR_SCHEDULE_PREPARE)(schedule_gui,schedule_gui,false);
                result="submitted";
            }
        }
        else if (operation == "station_count") {
            function<void(*)(void *,double)>(FR_PUSHNUMBER)(state,station_buttons.size());
            return 1;
        }
        else if (operation == "station_label" || operation == "station_point" || operation == "station_choose") {
            auto index=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,2,nullptr);
            if (!std::isfinite(index) || index<1 || index>station_buttons.size() || std::floor(index)!=index)
                throw std::runtime_error("Station choice is unavailable");
            auto found=station_buttons.begin();std::advance(found,static_cast<size_t>(index)-1);
            if (operation=="station_label") result=found->second;
            else {
                auto widget=found->first;
                auto rect=function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(widget);
                Pixel pixel{rect.x+rect.width/2,rect.y+rect.height/2};
                if (operation=="station_point") point_pixel(pixel);
                else tap_pixel("pick-item",pixel);
                result="submitted";
            }
        }
        else if (operation == "platform_new") {
            if (!surface_list) result="waiting";
            else {
                function<void(*)(void *)>(FR_NEW_PLATFORM)(surface_list);
                result="submitted";
            }
        }
        else if (operation == "platform_confirm") {
            if (!platform_dialog) result="waiting";
            else result=function<bool(*)(void *)>(FR_CONFIRM_PLATFORM)(platform_dialog) ? "submitted" : "rejected";
        }
        else if (operation == "rocket_launch") {
            if (!rocket_gui) result="waiting";
            else {
                auto target=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,2,nullptr);
                auto transport=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,3,nullptr)!=0;
                if (!std::isfinite(target) || target<1 || target>UINT32_MAX || std::floor(target)!=target)
                    throw std::runtime_error("Invalid platform index");
                function<void(*)(void *,uint32_t,bool,bool)>(FR_LAUNCH_ROCKET)(rocket_gui,static_cast<uint32_t>(target),true,transport);
                result="submitted";
            }
        }
        else if (operation == "remote_view") {
            auto player=function<void *(*)(void *,int)>(FR_PARSE_PLAYER)(state,2);
            auto local=player ? function<void *(*)(void **,void **)>(FR_LOCAL_PLAYER)(&player,&player+1) : nullptr;
            if (!local || local!=player) throw std::runtime_error("Remote view requires the local player");
            auto position=function<uint64_t(*)(void *,int,const char *)>(FR_POSITION_PARSE)(state,3,"position");
            auto surface=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,4,nullptr);
            auto zoom=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,5,nullptr);
            if (!std::isfinite(surface) || surface<1 || surface>UINT32_MAX || std::floor(surface)!=surface || !std::isfinite(zoom) || zoom<=0)
                throw std::runtime_error("Invalid remote surface or zoom");
            // Verified value ABI: GuiContext carries one opaque Player*. The game constructs
            // both input actions, including render mode, permissions and network submission.
            // LuaSurface.index is one-based; SurfaceIndex is zero-based.
            function<void(*)(void **,uint32_t,uint64_t,double)>(FR_OPEN_CHART)(&player,static_cast<uint32_t>(surface)-1,position,zoom);
            result="submitted";
        }
        else if (operation == "blueprint_import") {
            if (!app_manager) throw std::runtime_error("The application context is unavailable");
            auto value=string_value(state,2,size);
            if (!value) throw std::runtime_error("A blueprint string is required");
            std::string blueprint(value,size);
            function<void(*)(void *,const std::string &)>(FR_BLUEPRINT_IMPORT)(app_manager,blueprint);
            result="submitted";
        }
        else if (operation == "aim") {
            input_position=function<uint64_t(*)(void *, int, const char *)>(FR_POSITION_PARSE)(state,3,"position");
            position_override=true;
            result="submitted";
        }
        else if (operation == "tap" || operation == "point") {
            if (!game_map || !game_view) throw std::runtime_error("The game input context is unavailable");
            // MapPosition stays opaque: the game's Lua parser and projection own its representation.
            auto position = function<uint64_t(*)(void *, int, const char *)>(FR_POSITION_PARSE)(state, 3, "position");
            auto pixel = function<uint64_t(*)(void *, uint64_t)>(FR_SCREEN_POSITION)(game_view, position);
            if (operation == "point") {
                Pixel point; memcpy(&point, &pixel, sizeof point);
                point_pixel(point);
            } else {
                auto name = string_value(state, 2, size);
                if (!name) throw std::runtime_error("A control name is required");
                auto control = function<void *(*)(std::string_view)>(FR_FIND_CONTROL)(std::string_view(name, size));
                if (!control) throw std::runtime_error("Unknown game control");
                function<void(*)(void *, void *, uint64_t, bool)>(FR_TAP_CONTROL)(game_map, control, pixel, true);
            }
            result = "submitted";
        }
        else if (operation=="next-active-quick-bar" || operation=="previous-active-quick-bar") {
            if (!input_source) throw std::runtime_error("Quickbar changes require the input phase");
            result=function<bool(*)(void *,bool)>(FR_QUICKBAR_PAGE)(input_source,operation=="next-active-quick-bar") ? "submitted" : "waiting";
        }
        else if (operation == "grid_point" || operation == "grid_pick") {
            if (!equipment_grid_gui) result="waiting";
            else {
                auto x=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,2,nullptr);
                auto y=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,3,nullptr);
                if (!(x>0 && x<1 && y>0 && y<1)) throw std::runtime_error("Invalid equipment grid cell");
                auto rect=function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(equipment_grid_gui);
                Pixel pixel{rect.x+static_cast<int>(rect.width*x),rect.y+static_cast<int>(rect.height*y)};
                if (operation=="grid_point") {
                    // The game owns grid coordinate encoding and the normal synchronized GUI action.
                    equipment_position=function<uint64_t(*)(void *,int,const char *)>(FR_POSITION_PARSE)(state,4,"position");
                    equipment_position_override=true;
                    point_pixel(pixel);
                } else tap_pixel("pick-item",pixel);
                result="submitted";
            }
        }
        else if (operation == "slot_point" || operation == "slot_pick" || operation == "slot_split" || operation == "slot_transfer" || operation == "slot_open" || operation == "slot_filter" || operation == "slot_send") {
            StackCapture capture;
            // Let the official Lua getter resolve its actual stack. No Lua userdata or
            // inventory layout is reconstructed, including when the slot is empty.
            function<int(*)(void *,int,const char *,size_t)>(FR_GETFIELD)(state,2,"count",5);
            pop(state);
            if (!captured_stack) throw std::runtime_error("Cannot resolve this inventory slot");
            Rectangle rect{};
            {
                std::lock_guard<std::mutex> lock(slots_mutex);
                for (auto slot : inventory_slots) {
                    if (function<void *(*)(void *)>(FR_SLOT_STACK)(slot)!=captured_stack) continue;
                    auto candidate=function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(slot);
                    if (candidate.width>0 && candidate.height>0) { rect=candidate; break; }
                }
            }
            if (rect.width<=0 || rect.height<=0) result="waiting";
            else {
                Pixel pixel{rect.x+rect.width/2,rect.y+rect.height/2};
                if (operation=="slot_point") point_pixel(pixel);
                else tap_pixel(operation=="slot_split" ? "cursor-split" : operation=="slot_transfer" ? "fast-entity-transfer" : operation=="slot_send" ? "stack-transfer" : operation=="slot_open" ? "open-item" : operation=="slot_filter" ? "toggle-filter" : "pick-item",pixel);
                result="submitted";
            }
        }
        else if (operation == "cancel_point" || operation == "cancel_craft") {
            if (!character_view || !game_map) result="waiting";
            else {
                auto index=function<double(*)(void *,int,int *)>(FR_TONUMBER)(state,2,nullptr);
                if (index<1 || index>65535 || index!=static_cast<unsigned>(index)) throw std::runtime_error("Invalid crafting queue index");
                // This method returns a pixel bounding box (minimum, maximum), unlike agui rectangles.
                auto rect=function<Rectangle(*)(void *,unsigned)>(FR_CRAFT_QUEUE_RECT)(character_view,static_cast<unsigned>(index)-1);
                rect.width-=rect.x; rect.height-=rect.y;
                if (rect.width<=0 || rect.height<=0) result="waiting";
                else {
                    Pixel pixel{rect.x+rect.width/2,rect.y+rect.height/2};
                    if (operation=="cancel_point") point_pixel(pixel);
                    else tap_pixel("cancel-craft",pixel);
                    result="submitted";
                }
            }
        }
        else if (operation == "build" || operation == "build-ghost") {
            result=function<bool(*)(void *)>(operation=="build" ? FR_BUILD : FR_GHOST_BUILD)(input_source) ? "submitted" : "build was rejected";
        }
        else if (operation == "rotate" || operation == "reverse-rotate") {
            result=function<bool(*)(void *,bool)>(FR_ROTATE)(input_source,operation=="reverse-rotate") ? "submitted" : "waiting";
        }
        else if (operation == "close_gui") {
            result = function<bool(*)(void *)>(FR_CLOSE_GUI)(input_source) ? "submitted" : "waiting";
        }
        else if (operation == "recipe_open_point" || operation == "recipe_open") {
            if (!recipe_button || !game_map) result="waiting";
            else {
                auto rect=function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(recipe_button);
                if (rect.width<=0 || rect.height<=0) result="waiting";
                else {
                    Pixel pixel{rect.x+rect.width/2,rect.y+rect.height/2};
                    if (operation=="recipe_open_point") point_pixel(pixel);
                    else { recipe_list=nullptr; tap_pixel("pick-item",pixel); }
                    result="submitted";
                }
            }
        }
        else if (operation == "recipe_confirm") {
            if (!recipe_gui) result="waiting";
            else result=function<bool(*)(void *)>(FR_RECIPE_CONFIRM)(recipe_gui) ? "submitted" : "recipe confirmation was rejected";
        }
        else if (operation == "recipe") {
            if (!recipe_list) result="waiting";
            else {
                auto id=function<unsigned short(*)(void *,int,const char *)>(FR_RECIPE_PARSE)(state,2,"recipe");
                result=function<bool(*)(void *,unsigned short,bool)>(FR_SELECT_RECIPE)(recipe_list,id,true) ? "submitted" : "recipe is not available";
            }
        }
        else if (operation == "craft_reset") {
            if (crafting_gui) function<void(*)(void *,bool,bool)>(FR_CRAFT_REFRESH)(crafting_gui,false,false);
            result = "restored";
        }
        else if (operation == "craft_prepare") {
            if (!crafting_gui) result = "waiting";
            else {
                function<void(*)(void *, const std::string &)>(FR_CRAFT_SEARCH)(crafting_gui, std::string{});
                function<void(*)(void *,bool,bool)>(FR_CRAFT_REFRESH)(crafting_gui,false,false);
                function<void(*)(void *)>(FR_CRAFT_UNGROUP)(crafting_gui);
                result = "selected";
            }
        }
        else if (operation == "craft_scroll" || operation == "craft_point" || operation == "craft") {
            if (!crafting_gui || !game_map) result = "waiting";
            else {
                auto id = function<unsigned short(*)(void *, int, const char *)>(FR_RECIPE_PARSE)(state, 2, "recipe");
                std::function<bool(void *)> matches = [id](void *slot) {
                    return function<unsigned short(*)(void *)>(FR_RECIPE_ID)(slot) == id;
                };
                auto slot = function<void *(*)(void *, const std::function<bool(void *)> &)>(FR_RECIPE_FIND)(crafting_gui, matches);
                if (!slot) result = "waiting";
                else {
                    auto rect = function<Rectangle(*)(void *)>(FR_WIDGET_RECT)(slot);
                    if (rect.width<=0 || rect.height<=0) throw std::runtime_error("Recipe widget has no layout");
                    Pixel pixel{rect.x+rect.width/2,rect.y+rect.height/2};
                    if (operation == "craft_scroll") function<void(*)(void *,int,int)>(FR_SCROLL_VISIBLE)(slot,0,rect.height);
                    else if (operation == "craft_point") point_pixel(pixel);
                    else tap_pixel("craft",pixel);
                    result = "submitted";
                }
            }
        }
        else if (operation == "research_ready") result = technology_gui ? "ready" : "waiting";
        else if (operation == "research") {
            if (!technology_gui) result = "The technology window is unavailable";
            else {
                auto id = function<unsigned short(*)(void *, int, const char *)>(FR_TECH_PARSE)(state, 2, "technology");
                function<void(*)(void *, unsigned short)>(FR_RESEARCH)(technology_gui, id);
                result = "submitted";
            }
        } else if (operation == "close_research") {
            if (technology_gui) function<void(*)(void *)>(FR_RESEARCH_TOGGLE)(input_source);
            result = "submitted";
        } else result = "Unknown native action";
    } catch (const std::exception &error) { result = error.what(); }
    push_string(state, result.data(), result.size());
    return 1;
}
void bind(void *state) {
    if (!binding_requested) return;
    if (world_lua == state) return;
    if (lua(state, R"lua(
        return game and script and not game.simulation and script.mod_name == 'level' and 'world' or 'skip'
    )lua") != "world") return;
    function<void(*)(void *, void *, int)>(FR_PUSH_CLOSURE)(state, reinterpret_cast<void *>(send_result), 0);
    function<void(*)(void *, const char *)>(FR_SETGLOBAL)(state, "__factorio_mcp_send_v1");
    function<void(*)(void *, void *, int)>(FR_PUSH_CLOSURE)(state, reinterpret_cast<void *>(native_library), 0);
    function<void(*)(void *, const char *)>(FR_SETGLOBAL)(state, "__factorio_mcp_native_v1");
    if (lua(state, bootstrap) != "bound") throw std::runtime_error("Lua bridge installation did not complete");
    world_lua = state;
}
void submit(const FrRequest &request, const char *body) {
    if (!world || !world_lua) { reply_error(request.id, "An active world with a bound Lua bridge is required"); return; }
    if (request.operation != FR_LUA) { reply_error(request.id, "Unsupported resident operation"); return; }
    auto state = world_lua;
    try {
        const char source[] = "return __factorio_mcp_submit_v1(...)";
        Reader reader{source, sizeof source - 1};
        if (function<int(*)(void *, void *, void *, const char *, const char *)>(FR_LOAD)(
            state, reinterpret_cast<void *>(read_lua), &reader, "=factorio-submit", "t")) {
            pop(state);
            throw std::runtime_error("Cannot load Lua submission adapter");
        }
        push_string(state, request.id.value, strlen(request.id.value));
        push_string(state, body, request.length);
        function<void(*)(void *, double)>(FR_PUSHNUMBER)(state, request.timeout_ms);
        protected_call(state, 3, 0);
    } catch (const std::exception &error) { reply_error(request.id, error.what()); }
}
void pump() {
    if (client < 0) {
        client=accept4(listener,nullptr,nullptr,SOCK_CLOEXEC | SOCK_NONBLOCK);
        if (client>=0) {
            ucred peer{};socklen_t size=sizeof peer;
            if (getsockopt(client,SOL_SOCKET,SO_PEERCRED,&peer,&size) || peer.uid!=getuid()) disconnect();
        }
    }
    if (client < 0) return;
    for (unsigned n = 0; n < FR_QUEUE_CAPACITY; ++n) {
        char packet[sizeof(FrRequest) + FR_MAX_REQUEST];
        auto count = recv(client, packet, sizeof packet, MSG_DONTWAIT | MSG_TRUNC);
        if (count < 0) { if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) disconnect(); break; }
        if (!count) { disconnect(); break; }
        if (count < static_cast<ssize_t>(sizeof(FrRequest)) || count > static_cast<ssize_t>(sizeof packet)) { disconnect(); break; }
        FrRequest request; memcpy(&request, packet, sizeof request);
        if (request.length != count - sizeof request || !memchr(request.id.value, 0, sizeof request.id.value)) { disconnect(); break; }
        if (request.version!=FR_VERSION) {
            reply_error(request.id,"Resident version mismatch; restart Factorio before attaching this executable");
            continue;
        }
        if (request.operation == FR_BIND_WORLD) {
            if (!world || main_menu || descriptor.installed!=FR_HOOK_COUNT || request.expected_generation!=generation) {
                reply_error(request.id,"The world is unavailable or changed, or Lua adapters are absent; call status and attach again");
                continue;
            }
            binding_requested=true;
        }
        if (request.operation == FR_STATUS || request.operation == FR_BIND_WORLD) {
            FrStatus status{instance, generation, reinterpret_cast<uintptr_t>(&descriptor), world ? 1u : 0u,
                world_lua ? 1u : 0u, main_menu ? 1u : 0u, descriptor.installed, binding_requested ? 1u : 0u, 0};
            reply(request.id, reinterpret_cast<const char *>(&status), sizeof status);
        } else submit(request, packet + sizeof request);
    }
}
}

extern "C" __attribute__((visibility("hidden"))) void resident_enter(FrFrame *frame, void *) noexcept {
    auto id = static_cast<unsigned>(frame->branch);
    frame->branch = descriptor.patches[id].trampoline;
    if (id==FR_EVENT_PUSH && observing_pointer_entry) {
        auto event=reinterpret_cast<const SDL_Event *>(frame->rdi);
        if (event->type==SDL_WINDOWEVENT && event->window.event==SDL_WINDOWEVENT_ENTER &&
            event->window.windowID==function<uint32_t(*)(void *)>(FR_WINDOW_ID)(pointer_window)) pointer_added_entry=true;
    }
    // The resolved Lua destructor invalidates the scoped VM before memory is freed,
    // including reloads where the Scenario address itself does not change.
    if (id == FR_CLOSE && reinterpret_cast<void *>(frame->rdi) == world_lua) {
        set_world(0);
    }
    if (id == FR_WINDOW_POLL) game_window=reinterpret_cast<void *>(frame->rdi);
    if (id == FR_WINDOW_CLOSE && reinterpret_cast<void *>(frame->rdi)==game_window) {
        release_pointer();
        game_window=nullptr;
    }
    if (id == FR_TECH_CLOSE && reinterpret_cast<void *>(frame->rdi) == technology_gui) technology_gui = nullptr;
    if (id == FR_MAP_CLOSE && reinterpret_cast<void *>(frame->rdi) == game_map) game_map = nullptr;
    if (id == FR_VIEW_CLOSE && reinterpret_cast<void *>(frame->rdi) == game_view) game_view = nullptr;
    if (id == FR_WIDGET_CLOSE && reinterpret_cast<void *>(frame->rdi) == crafting_gui) crafting_gui = nullptr;
    if (id==FR_WIDGET_CLOSE || id==FR_NUMBER_CLOSE || id==FR_CHECK_CLOSE || id==FR_LABELED_CLOSE) {
        for (auto &control : setting_controls)
            if (control.receiver==reinterpret_cast<void *>(frame->rdi)) control.receiver=nullptr;
    }
    if (id==FR_LABELED_CLOSE) switch_labels.erase(reinterpret_cast<void *>(frame->rdi));
    if (id==FR_WIDGET_CLOSE) {
        for (auto &[owner,labels] : switch_labels)
            for (auto &label : labels) if (label==reinterpret_cast<void *>(frame->rdi)) label=nullptr;
    }
    if (active && world && id==FR_LABELED_CREATE && return_count<std::size(returns) && constructing_switches.size()<128) {
        constructing_switches.push_back(reinterpret_cast<void *>(frame->rdi));
        returns[return_count++]={frame->return_address,frame->rdi,generation,id,nullptr};
        frame->return_address=reinterpret_cast<uintptr_t>(resident_after);
        return;
    }
    if (world && id==FR_WIDGET_CLICK && frame->rdx && !constructing_switches.empty() && switch_labels.size()<128) {
        const auto &callback=*reinterpret_cast<const std::function<void()> *>(frame->rdx);
        if (callback_has_type(callback,"LabeledSwitch::init()::$_2"))
            switch_labels[constructing_switches.back()][0]=reinterpret_cast<void *>(frame->rdi);
        else if (callback_has_type(callback,"LabeledSwitch::init()::$_3"))
            switch_labels[constructing_switches.back()][1]=reinterpret_cast<void *>(frame->rdi);
    }
    if (world && frame->rdx && (id==FR_NUMBER_REGISTER || id==FR_CHECK_REGISTER || id==FR_SWITCH_REGISTER || id==FR_CHECK_VOID_REGISTER)) {
        for (auto &control : setting_controls) {
            if (control.registration!=id) continue;
            bool matches=id==FR_CHECK_REGISTER
                ? callback_has_type(*reinterpret_cast<const std::function<void(bool)> *>(frame->rdx),control.callback_type)
                : callback_has_type(*reinterpret_cast<const std::function<void()> *>(frame->rdx),control.callback_type);
            if (matches) control.receiver=reinterpret_cast<void *>(frame->rdi);
        }
    }
    if (id == FR_WIDGET_CLOSE) {
        station_buttons.erase(reinterpret_cast<void *>(frame->rdi));
        schedule_go_buttons.erase(reinterpret_cast<void *>(frame->rdi));
        schedule_remove_buttons.erase(reinterpret_cast<void *>(frame->rdi));
        logistic_checks.erase(reinterpret_cast<void *>(frame->rdi));
        for (auto receiver : {&platform_switch,&drop_button,&drop_list})
            if (*receiver==reinterpret_cast<void *>(frame->rdi)) *receiver=nullptr;
        if (reinterpret_cast<void *>(frame->rdi)==schedule_gui) { schedule_gui=nullptr;station_buttons.clear(); }
        if (reinterpret_cast<void *>(frame->rdi)==surface_list) surface_list=nullptr;
        if (reinterpret_cast<void *>(frame->rdi)==platform_dialog) platform_dialog=nullptr;
        if (reinterpret_cast<void *>(frame->rdi)==rocket_gui) rocket_gui=nullptr;
        if (reinterpret_cast<void *>(frame->rdi)==equipment_grid_gui) { equipment_grid_gui=nullptr; equipment_position_override=false; }
        if (reinterpret_cast<void *>(frame->rdi)==assembling_gui) assembling_gui=nullptr;
        if (reinterpret_cast<void *>(frame->rdi)==recipe_list) recipe_list=nullptr;
        if (reinterpret_cast<void *>(frame->rdi)==recipe_button) recipe_button=nullptr;
        std::lock_guard<std::mutex> lock(slots_mutex);
        inventory_slots.erase(reinterpret_cast<void *>(frame->rdi));
    }
    if (id == FR_CHARACTER_CLOSE && reinterpret_cast<void *>(frame->rdi)==character_view) character_view=nullptr;
    if (id==FR_GRID_PAINT && world) equipment_grid_gui=reinterpret_cast<void *>(frame->rdi);
    if (id==FR_SURFACE_LIST && world) surface_list=reinterpret_cast<void *>(frame->rdi);
    if (id==FR_PLATFORM_DIALOG && world) platform_dialog=reinterpret_cast<void *>(frame->rdi);
    // Scope captured controls by resolved caller ranges, without object offsets or closure layouts.
    auto in_caller=[&](FrFunction caller) {
        const auto &entry=config.functions[caller];
        return frame->return_address>=entry.address && frame->return_address<entry.address+entry.size;
    };
    if (world && id==FR_SWITCH_REGISTER && (in_caller(FR_PLATFORM_FRAME) || in_caller(FR_TRAIN_CREATE))) platform_switch=reinterpret_cast<void *>(frame->rdi);
    if (world && id==FR_WIDGET_TOGGLE && in_caller(FR_STATION_CREATE) && schedule_go_buttons.size()<512)
        schedule_go_buttons.insert(reinterpret_cast<void *>(frame->rdi));
    if (world && id==FR_CHECK_REGISTER && in_caller(FR_LOGISTIC_SECTION_CREATE) && logistic_checks.size()<512)
        logistic_checks.insert(reinterpret_cast<void *>(frame->rdi));
    if (world && id==FR_WIDGET_CLICK && in_caller(FR_STATION_CREATE) && schedule_remove_buttons.size()<512) {
        // Only inspect the borrowed std::function while onClick owns its argument.
        // The callback is never copied or invoked; ordinary clicks use the game's listener.
        const auto &callback=*reinterpret_cast<const std::function<void()> *>(frame->rdx);
        if (callback_has_type(callback,"ScheduleStationGui::ScheduleStationGui(ScheduleGui&, DragPane&, ScheduleRecordPosition)::$_0"))
            schedule_remove_buttons.insert(reinterpret_cast<void *>(frame->rdi));
    }
    if (world && id==FR_WIDGET_TEXT_SET && in_caller(FR_REMOTE_GUI_UPDATE)) drop_button=reinterpret_cast<void *>(frame->rdi);
    if (world && id==FR_LIST_COUNT && in_caller(FR_DROP_SELECTOR)) drop_list=reinterpret_cast<void *>(frame->rdi);
    if (id==FR_SCHEDULE_GUI && world) schedule_gui=reinterpret_cast<void *>(frame->rdi);
    if (id==FR_WIDGET_CLICK && world && schedule_gui) {
        auto &updater=config.functions[FR_STATIONS_UPDATE];
        if (frame->return_address>=updater.address && frame->return_address<updater.address+updater.size && station_buttons.size()<512) {
            auto widget=reinterpret_cast<void *>(frame->rdi);
            station_buttons[widget]=function<const std::string &(*)(void *)>(FR_WIDGET_TEXT)(widget);
        }
    }

    if (id==FR_SLOT_PAINT && world) {
        std::lock_guard<std::mutex> lock(slots_mutex);
        if (inventory_slots.size()<4096) inventory_slots.insert(reinterpret_cast<void *>(frame->rdi));
    }
    if (id == FR_CHARACTER_VIEW && world) character_view=reinterpret_cast<void *>(frame->rdi);
    if (id==FR_RECIPE_GUI && world) recipe_gui=reinterpret_cast<void *>(frame->rdi);
    if (id==FR_RECIPE_CLOSE && reinterpret_cast<void *>(frame->rdi)==recipe_gui) recipe_gui=nullptr;
    if (id == FR_RECIPE_LIST && world) recipe_list=reinterpret_cast<void *>(frame->rdi);
    if (id == FR_ASSEMBLING_GUI && world) {
        assembling_gui=reinterpret_cast<void *>(frame->rdi);
        auto &rocket_logic=config.functions[FR_ROCKET_LOGIC];
        if (frame->return_address>=rocket_logic.address && frame->return_address<rocket_logic.address+rocket_logic.size)
            rocket_gui=assembling_gui;
    }
    if (id == FR_CRAFT_GUI && world) crafting_gui = reinterpret_cast<void *>(frame->rdi);
    // Capture the recipe-change button passed to its real constructor, without private offsets.
    auto &assembler_constructor=config.functions[FR_ASSEMBLING_CREATE];
    bool recipe_button_call=id==FR_ICON_CREATE && frame->return_address>=assembler_constructor.address &&
        frame->return_address<assembler_constructor.address+assembler_constructor.size;
    if (active && world && (id == FR_SLOT_CREATE || recipe_button_call) && return_count < std::size(returns)) {
        returns[return_count++]={frame->return_address,frame->rdi,generation,id,nullptr};
        frame->return_address=reinterpret_cast<uintptr_t>(resident_after);
        return;
    }
    if (active && id==FR_GRID_CURSOR && equipment_position_override && reinterpret_cast<void *>(frame->rdi)==equipment_grid_gui && return_count<std::size(returns)) {
        returns[return_count++]={frame->return_address,frame->rdi,generation,id,nullptr};
        frame->return_address=reinterpret_cast<uintptr_t>(resident_after);
        return;
    }
    if (id == FR_CONTROL && collect_control) {
        collect_control->values.insert(reinterpret_cast<void *>(frame->rdi));
        return;
    }
    if ((id==FR_STACK_ENTITY || id==FR_STACK_CONTROLLER) && capturing_stack && return_count<std::size(returns)) {
        returns[return_count++]={frame->return_address,0,generation,id,nullptr};
        frame->return_address=reinterpret_cast<uintptr_t>(resident_after);
        return;
    }
    if (active && id==FR_CURSOR_PLAYER && position_override && (in_input || input_source) && return_count<std::size(returns)) {
        returns[return_count++]={frame->return_address,input_position,generation,id,nullptr};
        frame->return_address=reinterpret_cast<uintptr_t>(resident_after);
        return;
    }
    if (!active || executing) return;
    try {
        if (id == FR_APP) {
            app_manager=reinterpret_cast<void *>(frame->rdi);
            main_menu=function<bool(*)(void *)>(FR_IS_MENU)(reinterpret_cast<void *>(frame->rdi));
            if (main_menu) set_world(0);
            executing = true;
            pump();
            executing = false;
        } else if (id == FR_UPDATE) set_world(frame->rsi);
        else if (id == FR_MAP && world) game_map = reinterpret_cast<void *>(frame->rdi);
        else if (id == FR_VIEW && world) game_view = reinterpret_cast<void *>(frame->rdi);
        else if (id == FR_TECH_PAINT) technology_gui = reinterpret_cast<void *>(frame->rdi);
        else if (id == FR_INPUT && world_lua) {
            executing = true;
            input_source = reinterpret_cast<void *>(frame->rdi);
            position_override=false;
            // Ask the game to evaluate each named control and capture its real binding receivers.
            // This follows optimized callees without reading private ControlInput field offsets.
            for (auto &control : controls) {
                control.values.clear();
                control.address=function<void *(*)(std::string_view)>(FR_FIND_CONTROL)(control.name);
                if (!control.address) continue;
                collect_control=&control;
                function<bool(*)(void *,bool,bool,bool,bool)>(FR_CONTROL_READ)(control.address,false,false,false,false);
                collect_control=nullptr;
            }
            try { lua(world_lua, "__factorio_mcp_phase_v1('input'); return ''"); } catch (...) { }
            input_source = nullptr;
            executing = false;
            if (return_count < std::size(returns)) {
                in_input = true;
                returns[return_count++] = {frame->return_address, 0, generation, id, nullptr};
                frame->return_address = reinterpret_cast<uintptr_t>(resident_after);
            }
        }
        else if (id == FR_CONTROL && in_input && world_lua && return_count < std::size(returns)) {
            for (auto &control : controls) {
                if (!control.values.count(reinterpret_cast<void *>(frame->rdi))) continue;
                returns[return_count++] = {frame->return_address, 0, generation, id, control.name};
                frame->return_address = reinterpret_cast<uintptr_t>(resident_after);
                break;
            }
        }
        else if ((id == FR_PCALL || id == FR_GC) && world && binding_requested && !world_lua) {
            // Scenario maintenance reaches idle VMs even when no Lua event handler is registered.
            // Bind after the real call returns, outside GC internals and without reading VM/object layouts.
            auto &event = config.functions[id == FR_PCALL ? FR_EVENT : FR_SCENARIO_UPDATE];
            if (frame->return_address >= event.address && frame->return_address < event.address + event.size && return_count < std::size(returns)) {
                returns[return_count++] = {frame->return_address, frame->rdi, generation, id, nullptr};
                frame->return_address = reinterpret_cast<uintptr_t>(resident_after);
            }
        }
    } catch (...) { executing = false; }
}
extern "C" __attribute__((visibility("hidden"))) void resident_leave(FrFrame *frame, void *) noexcept {
    auto saved = returns[--return_count]; frame->branch = saved.address;
    if (saved.hook == FR_INPUT) { in_input = false; position_override=false; return; }
    if (saved.generation != generation) return;
    if (saved.hook==FR_LABELED_CREATE) { if (!constructing_switches.empty()) constructing_switches.pop_back();return; }
    if (saved.hook==FR_GRID_CURSOR) {
        if (equipment_position_override && reinterpret_cast<void *>(saved.lua)==equipment_grid_gui) frame->rax=equipment_position;
        return;
    }
    if (saved.hook==FR_STACK_ENTITY || saved.hook==FR_STACK_CONTROLLER) { captured_stack=reinterpret_cast<void *>(frame->rax); return; }
    if (saved.hook == FR_CURSOR_PLAYER) { frame->rax=saved.lua; return; }
    if (saved.hook == FR_ICON_CREATE) { recipe_button=reinterpret_cast<void *>(saved.lua); return; }
    if (saved.hook == FR_SLOT_CREATE) {
        std::lock_guard<std::mutex> lock(slots_mutex);
        if (inventory_slots.size()<4096) inventory_slots.insert(reinterpret_cast<void *>(saved.lua));
        return;
    }
    executing = true;
    if (saved.hook == FR_CONTROL && world_lua) {
        control_frame = frame;
        control_name = saved.control;
        try { lua(world_lua, "__factorio_mcp_phase_v1('control'); return ''"); } catch (...) { }
        control_frame = nullptr;
        control_name = nullptr;
    } else {
        try { bind(reinterpret_cast<void *>(saved.lua)); } catch (...) { world_lua = nullptr; }
    }
    executing = false;
}
extern "C" __attribute__((visibility("default"))) void factorio_resident_activate_v1(unsigned count) {
    descriptor.installed=count;
    active=true;
}
extern "C" __attribute__((visibility("default"))) const FrDescriptor *factorio_resident_prepare_v1(const FrConfig *configuration) noexcept {
    if (descriptor.version == FR_VERSION) return &descriptor;
    try {
        if (configuration->version != FR_VERSION || !configuration->script || configuration->script_length > FR_MAX_REQUEST)
            throw std::runtime_error("Invalid resident configuration");
        config = *configuration;
        bootstrap.assign(configuration->script, configuration->script_length);
        config.script = nullptr;
        unsigned a, b, c, d;
        if (__get_cpuid(1, &a, &b, &c, &d) && (c & bit_OSXSAVE)) {
            unsigned low, high; asm volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(0));
            resident_xsave_mask = (uint64_t(high) << 32) | low;
            __cpuid_count(0xD, 0, a, b, c, d);
            if (b > 65536) throw std::runtime_error("Unsupported extended register state size");
        }
        for (unsigned i = 0; i < FR_HOOK_COUNT; ++i) resident_prepare_hook(descriptor.patches[i], config.functions[i], i);
        listener = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (listener < 0) throw std::runtime_error("Cannot create resident IPC socket");
        sockaddr_un address{}; address.sun_family = AF_UNIX;
        auto name = resident_socket_name(getpid());
        if (name.size() + 1 > sizeof address.sun_path) throw std::runtime_error("IPC name too long");
        memcpy(address.sun_path + 1, name.data(), name.size());
        if (::bind(listener, reinterpret_cast<sockaddr *>(&address), offsetof(sockaddr_un, sun_path) + 1 + name.size()) || listen(listener, 1))
            throw std::runtime_error("Cannot bind resident IPC socket");
        if (getrandom(&instance, sizeof instance, 0) != sizeof instance) throw std::runtime_error("Cannot create resident instance identifier");
        descriptor.activate = reinterpret_cast<uintptr_t>(factorio_resident_activate_v1);
        descriptor.count = FR_HOOK_COUNT; descriptor.version = FR_VERSION;
    } catch (const std::exception &error) {
        for (auto &patch : descriptor.patches) resident_discard_hook(patch);
        if (listener >= 0) close(listener);
        listener = -1;
        strncpy(descriptor.error, error.what(), sizeof descriptor.error - 1);
    }
    return &descriptor;
}
