#pragma once
#include "bridge.h"
#include <vector>

// Resolves a complete postorder snapshot during the same safe point as widget dispatch.
std::vector<int> selectWidgets(FmResult &result, const FmAction &action);

// Expands explicit matches to their subtrees without interpreting widget properties.
std::vector<int> observationWidgets(const FmResult &result, bool filtered);
