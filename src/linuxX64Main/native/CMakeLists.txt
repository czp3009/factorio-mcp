cmake_minimum_required(VERSION 3.24)
project(factorio-mcp LANGUAGES C CXX ASM)

if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux" OR NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|amd64|AMD64)$")
    message(FATAL_ERROR "The injector currently supports Linux x86-64")
endif()

include(FetchContent)
# Header-only formatting keeps the Kotlin/Native static bridge self-contained.
FetchContent_Declare(fmt
    URL https://codeload.github.com/fmtlib/fmt/tar.gz/refs/tags/11.2.0
    URL_HASH SHA256=bc23066d87ab3168f27cef3e97d545fa63314f5c79df5ea444d41d56f962c6af
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(fmt)
set_target_properties(fmt PROPERTIES EXCLUDE_FROM_ALL TRUE)
# Kotlin/Native links against its portable sysroot; diagnostics need no locale facets.
target_compile_definitions(fmt-header-only INTERFACE FMT_USE_LOCALE=0)
# SDL's published event ABI supplies input structures; no SDL library is injected.
FetchContent_Declare(sdl_headers
    URL https://codeload.github.com/libsdl-org/SDL/tar.gz/refs/tags/release-2.30.12
    URL_HASH SHA256=560da2e54dd8af933e35bd08fb1b6cf80d4f6938c67710fecf13b7e9bdd6c47e
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    SOURCE_SUBDIR include)
FetchContent_MakeAvailable(sdl_headers)

# Decode instructions only. Detours, lifecycle and injection are implemented here.
set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_X86_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
set(CAPSTONE_INSTALL OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(capstone
    URL https://codeload.github.com/capstone-engine/capstone/tar.gz/refs/tags/5.0.6
    URL_HASH SHA256=240ebc834c51aae41ca9215d3190cc372fd132b9c5c8aa2d5f19ca0c325e28f9
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(capstone)

add_library(factorio_resident SHARED resident.cpp resident_hooks.cpp resident_hooks.S)
target_compile_features(factorio_resident PRIVATE cxx_std_17)
target_compile_options(factorio_resident PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_include_directories(factorio_resident PRIVATE "${sdl_headers_SOURCE_DIR}/include")
target_link_libraries(factorio_resident PRIVATE capstone)

add_executable(payload payload.c traps.S)
set_target_properties(payload PROPERTIES SUFFIX ".elf" POSITION_INDEPENDENT_CODE ON)
target_compile_options(payload PRIVATE -O2 -ffreestanding -fno-stack-protector
    -fno-asynchronous-unwind-tables -fno-unwind-tables -fcf-protection=none)
target_link_options(payload PRIVATE -nostdlib -no-pie
    "LINKER:-T,${CMAKE_CURRENT_SOURCE_DIR}/payload.ld" "LINKER:--no-undefined" "LINKER:--build-id=none")
set_property(TARGET payload APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/payload.ld")

add_custom_command(OUTPUT payload_blob.h
    COMMAND "${CMAKE_OBJCOPY}" -O binary --only-section=.text "$<TARGET_FILE:payload>" payload.bin
    COMMAND "${CMAKE_COMMAND}" "-DNM=${CMAKE_NM}" "-DELF=$<TARGET_FILE:payload>"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedPayload.cmake"
    DEPENDS payload cmake/EmbedPayload.cmake
    BYPRODUCTS payload.bin
    VERBATIM)

add_custom_target(payload_blob DEPENDS payload_blob.h)

file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/resident_blob.S" CONTENT [=[
.section .rodata
.balign 16
.global factorio_resident_start
.global factorio_resident_end
factorio_resident_start:
.incbin "$<TARGET_FILE:factorio_resident>"
factorio_resident_end:
.section .note.GNU-stack,"",@progbits
]=])
add_library(resident_blob OBJECT "${CMAKE_CURRENT_BINARY_DIR}/resident_blob.S")
add_dependencies(resident_blob factorio_resident)
set_source_files_properties("${CMAKE_CURRENT_BINARY_DIR}/resident_blob.S" PROPERTIES
    OBJECT_DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/libfactorio_resident.so")

add_library(factorio_bridge STATIC bridge.cpp debug_image.cpp $<TARGET_OBJECTS:resident_blob>)
add_dependencies(factorio_bridge payload_blob)
target_link_libraries(factorio_bridge PRIVATE fmt::fmt-header-only)
target_compile_features(factorio_bridge PRIVATE cxx_std_17)
target_compile_options(factorio_bridge PRIVATE -Wall -Wextra -Werror)
target_include_directories(factorio_bridge PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
set_target_properties(factorio_bridge PROPERTIES POSITION_INDEPENDENT_CODE ON)

include(CTest)
if(BUILD_TESTING)
    add_executable(resident_delivery_test ../../linuxX64Test/native/resident_delivery_test.cpp
        resident_hooks.cpp resident_hooks.S)
    target_compile_features(resident_delivery_test PRIVATE cxx_std_17)
    target_compile_options(resident_delivery_test PRIVATE -Wall -Wextra -Werror)
    target_include_directories(resident_delivery_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${sdl_headers_SOURCE_DIR}/include")
    target_link_libraries(resident_delivery_test PRIVATE capstone)
    add_test(NAME resident_delivery_is_independent_of_mcp_lifetime COMMAND resident_delivery_test)
    set_tests_properties(resident_delivery_is_independent_of_mcp_lifetime PROPERTIES TIMEOUT 5)
    add_executable(resident_hooks_test ../../linuxX64Test/native/resident_hooks_test.cpp
        ../../linuxX64Test/native/resident_hooks_fixture.S resident_hooks.cpp resident_hooks.S)
    target_compile_features(resident_hooks_test PRIVATE cxx_std_17)
    target_compile_options(resident_hooks_test PRIVATE -Wall -Wextra -Werror)
    target_include_directories(resident_hooks_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    target_link_libraries(resident_hooks_test PRIVATE capstone)
    add_test(NAME resident_preserves_registers_and_relocates_code COMMAND resident_hooks_test)
    set_tests_properties(resident_preserves_registers_and_relocates_code PROPERTIES TIMEOUT 10)
    add_executable(trace_lifecycle_test ../../linuxX64Test/native/trace_lifecycle_test.cpp debug_image.cpp $<TARGET_OBJECTS:resident_blob>)
    add_dependencies(trace_lifecycle_test payload_blob)
    target_compile_features(trace_lifecycle_test PRIVATE cxx_std_17)
    target_include_directories(trace_lifecycle_test PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
    target_link_libraries(trace_lifecycle_test PRIVATE ${CMAKE_DL_LIBS} pthread fmt::fmt-header-only)
    add_test(NAME trace_restores_new_threads COMMAND trace_lifecycle_test)
    set_tests_properties(trace_restores_new_threads PROPERTIES TIMEOUT 30)
    add_test(NAME trace_reports_native_abort COMMAND trace_lifecycle_test abort)
    set_tests_properties(trace_reports_native_abort PROPERTIES TIMEOUT 10)
    add_executable(debug_image_test ../../linuxX64Test/native/debug_image_test.cpp debug_image.cpp)
    target_compile_features(debug_image_test PRIVATE cxx_std_17)
    target_compile_options(debug_image_test PRIVATE -g -O0 -fno-pie)
    target_link_options(debug_image_test PRIVATE -no-pie)
    target_link_libraries(debug_image_test PRIVATE ${CMAKE_DL_LIBS} fmt::fmt-header-only)
    target_include_directories(debug_image_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    add_test(NAME debug_info_resolves_addresses COMMAND debug_image_test)
    add_test(NAME debug_info_strip_fixture COMMAND "${CMAKE_OBJCOPY}" --strip-debug
        "$<TARGET_FILE:debug_image_test>" "${CMAKE_CURRENT_BINARY_DIR}/stripped-fixture")
    set_tests_properties(debug_info_strip_fixture PROPERTIES FIXTURES_SETUP stripped)
    add_test(NAME debug_info_rejects_missing_dwarf COMMAND debug_image_test "${CMAKE_CURRENT_BINARY_DIR}/stripped-fixture")
    set_tests_properties(debug_info_rejects_missing_dwarf PROPERTIES FIXTURES_REQUIRED stripped)
endif()
