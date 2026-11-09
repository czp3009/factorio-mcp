#pragma once
#include "world_query.h"

bool hasGuiPredicates(const FmAction &action);
void prepareScriptGui(const Symbols &symbols, void *game, const FmWorldQuery &query, const FmAction &action,
                      const std::vector<void *> &widgets, FmResult &result, ObservationStorage &storage,
                      const volatile LONG *cancel);
void observeScriptGui(const Symbols &symbols, void *game, const FmWorldQuery &query, const std::vector<int> &observed,
                      FmResult &result, ObservationStorage &storage);
