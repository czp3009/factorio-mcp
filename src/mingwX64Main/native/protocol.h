#pragma once
#include "bridge.h"
#include <stdexcept>
#include <string>
#include <vector>
#include <windows.h>

using Symbol = FmSymbol;

inline std::wstring mappingName(DWORD pid) {
    return L"Local\\factorio-mcp-" + std::to_wstring(pid);
}

inline void require(bool condition, const char *message) {
    if (!condition)
        throw std::runtime_error(message);
}
