#pragma once
#include "observation_storage.h"

void queryWorld(const Symbols &symbols, void *game, const FmWorldQuery &query, FmResult &result,
                ObservationStorage &storage, std::string_view uiIndices = {});
