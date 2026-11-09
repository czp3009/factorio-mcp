#pragma once
#include "ui_snapshot.h"

int prepareScriptGui(const std::vector<uintptr_t> &widgets, const FmLinuxUiSelector *selector,
                      const uint32_t *cancel, UiSnapshot &snapshot);
void observeScriptGui(const std::vector<int> &observed, const uint32_t *cancel, UiSnapshot &snapshot);
