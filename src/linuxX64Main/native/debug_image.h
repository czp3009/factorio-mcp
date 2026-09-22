#pragma once
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace factorio {
struct Function {
    std::string name;
    uint64_t address = 0, size = 0, file_offset = 0;
    bool dwarf = false;
};
// Reads the developer-shipped ELF symbol/DWARF sections, never an offset
// manifest.
class DebugImage {
  public:
    explicit DebugImage(const std::string &path,
                        std::vector<std::string> additional_functions = {});
    ~DebugImage();
    DebugImage(const DebugImage &) = delete;
    DebugImage &operator=(const DebugImage &) = delete;
    const Function &function(const std::string &name) const;
    const unsigned char *bytes(const Function &fn) const;
    std::string build_id;
    uint64_t first_load_address = 0;

  private:
    struct Section {
        uint64_t offset, size, address, flags, type, link, entry_size;
    };
    int fd_ = -1;
    const unsigned char *data_ = nullptr;
    size_t size_ = 0;
    std::map<std::string, Section> sections_;
    std::map<std::string, Function> functions_;
    std::vector<std::string> additional_functions_;
    void parse();
    void validate_dwarf();
    const unsigned char *range(uint64_t offset, uint64_t length) const;
};
inline constexpr const char *game_update =
    "MainLoop::gameUpdateStep(MultiplayerManagerBase*, Scenario*, AppManager*, "
    "MainLoop::HeavyMode)";
inline constexpr const char *app_process = "AppManager::process()";
inline constexpr const char *loading_render =
    "LoadingSplashScreen::render(bool)";
inline constexpr const char *app_in_menu = "AppManager::isAppInMenu() const";
inline constexpr const char *event_dispatch =
    "LuaGameScript::runEventHandler(LuaEventType, int)";
inline constexpr const char *input_phase =
    "PlayerInputSource::sendStateChanges()";
inline constexpr const char *open_research =
    "PlayerInputSource::processOpenTechnologyGui()";
inline constexpr const char *start_research =
    "TechnologyGui::startResearch(ID<TechnologyPrototype, unsigned short>)";
inline constexpr const char *research_paint =
    "TechnologyGui::paintBackground(agui::PaintEvent const&, agui::Point "
    "const&)";
// The Itanium ABI name selects the base-object destructor, not the deleting
// overload.
inline constexpr const char *research_destructor = "_ZN13TechnologyGuiD2Ev";
inline constexpr const char *technology_parser =
    "ID<TechnologyPrototype, unsigned short> "
    "LuaHelper::parse<ID<TechnologyPrototype, unsigned short> >(lua_State*, "
    "int, char const*)";
inline constexpr const char *map_update = "Map::updateEntities()";
inline constexpr const char *map_destructor = "_ZN3MapD2Ev";
inline constexpr const char *view_update = "GameView::update(bool)";
inline constexpr const char *view_destructor = "_ZN8GameViewD2Ev";
inline constexpr const char *find_control =
    "ControlInput::findControlInput(std::basic_string_view<char, "
    "std::char_traits<char> >)";
inline constexpr const char *tap_control =
    "InputEventSender::tapControl(Map&, ControlInput const&, PixelPosition, "
    "bool)";
inline constexpr const char *position_parser =
    "MapPosition LuaHelper::parsePosition<MapPosition>(lua_State*, int, char "
    "const*)";
inline constexpr const char *screen_position =
    "GameView::getScreenPosition(MapPosition) const";
} // namespace factorio
