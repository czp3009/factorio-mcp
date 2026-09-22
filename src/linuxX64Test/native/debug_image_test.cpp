#include "debug_image.h"
#include <iostream>
#include <stdexcept>

// A synthetic game ABI. Its addresses and DWARF are supplied by this build's
// compiler/linker, independently of the installed Factorio version.
struct MultiplayerManagerBase {};
struct Scenario {};
struct AppManager { void process(); bool isAppInMenu() const; };
void AppManager::process() {}
bool AppManager::isAppInMenu() const { return false; }
namespace MainLoop {
enum class HeavyMode {};
void gameUpdateStep(MultiplayerManagerBase*, Scenario*, AppManager*, HeavyMode) {}
}
enum class LuaEventType {};
struct LuaGameScript { void runEventHandler(LuaEventType, int); };
void LuaGameScript::runEventHandler(LuaEventType, int) {}
extern "C" {
void lua_load() {}
void lua_pcallk() {}
void lua_tolstring() {}
void lua_settop() {}
void lua_type() {}
}

int main(int argc, char **argv) {
    try {
        factorio::DebugImage image(argc > 1 ? argv[1] : "/proc/self/exe");
        if (argc > 1) throw std::runtime_error("stripped fixture was accepted");
        if (image.function("lua_load").address != reinterpret_cast<uintptr_t>(&lua_load) ||
            image.function(factorio::game_update).address != reinterpret_cast<uintptr_t>(&MainLoop::gameUpdateStep))
            throw std::runtime_error("resolved addresses do not match linked functions");
        std::cout << "ELF symbols and DWARF resolved compiler-selected addresses\n";
        return 0;
    } catch (const std::exception &e) {
        if (argc > 1 && std::string(e.what()).find("DWARF sections are missing") != std::string::npos) return 0;
        std::cerr << e.what() << '\n';
        return 1;
    }
}
