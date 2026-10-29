#pragma once
#include "observation_storage.h"
#include <vector>

// Resolves a complete postorder snapshot during the same safe point as widget dispatch.
std::vector<int> selectWidgets(FmResult &result, ObservationStorage &storage, const FmAction &action,
                               const volatile LONG *cancel = nullptr);

// Expands explicit matches to their subtrees without interpreting widget properties.
std::vector<int> observationWidgets(const FmResult &result, const ObservationStorage &storage, bool filtered);
