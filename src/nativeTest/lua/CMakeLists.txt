cmake_minimum_required(VERSION 3.24)
project(query_lua_fixture LANGUAGES C)

include(FetchContent)
# Official source and checksum: https://www.lua.org/ftp/
FetchContent_Declare(lua
    URL https://www.lua.org/ftp/lua-5.2.4.tar.gz
    URL_HASH SHA256=b9e2e4aad6789b3b63a056d442f7b39f0ecfca3ae0f1fc0ae4e9614401b69f4b
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(lua)
file(GLOB LUA_SOURCES CONFIGURE_DEPENDS "${lua_SOURCE_DIR}/src/*.c")
list(FILTER LUA_SOURCES EXCLUDE REGEX "/lua(c)?\\.c$")
add_library(query_lua_fixture SHARED ${LUA_SOURCES} protected_fixture.c)
target_include_directories(query_lua_fixture PUBLIC "${lua_SOURCE_DIR}/src")
if(WIN32)
    target_compile_definitions(query_lua_fixture PRIVATE LUA_BUILD_AS_DLL)
    set_property(TARGET query_lua_fixture PROPERTY MSVC_RUNTIME_LIBRARY MultiThreaded)
else()
    target_link_libraries(query_lua_fixture PRIVATE m)
endif()
