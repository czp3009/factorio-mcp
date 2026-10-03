cmake_minimum_required(VERSION 3.24)
project(factorio_bridge LANGUAGES CXX ASM)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_MSVC_RUNTIME_LIBRARY MultiThreaded)
add_subdirectory("../../nativeMain/native" "${CMAKE_CURRENT_BINARY_DIR}/common")

add_library(factorio_bridge SHARED
    resident.cpp ui_selector.cpp ui_capture.cpp detour.cpp screenshot.cpp controls.cpp frontend_input.cpp
    widget_properties.cpp world_query.cpp viewport.cpp resident_input.cpp input_events.cpp
    chat.cpp
)
target_link_libraries(factorio_bridge PRIVATE factorio_input_sequence ole32 windowscodecs)
target_compile_definitions(factorio_bridge PRIVATE FM_BUILD WIN32_LEAN_AND_MEAN NOMINMAX _ITERATOR_DEBUG_LEVEL=0)
target_compile_options(factorio_bridge PRIVATE /O2 /EHsc /W4)
