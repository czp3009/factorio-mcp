#pragma once
#include "ui_snapshot.h"

bool validNumberLayout(const FmLinuxNumberLayout &layout);
int collectNumber(uintptr_t widget, const FmLinuxNumberLayout &layout, FmLinuxUiNode &node);
