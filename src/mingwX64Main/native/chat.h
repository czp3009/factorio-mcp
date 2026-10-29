#pragma once
#include "observation_storage.h"

void readChat(const Symbols &symbols, void *game, FmChatSnapshot &result, ObservationStorage &storage,
              const volatile LONG *cancel = nullptr);
void sendChat(const Symbols &symbols, void *game, const FmChatRequest &request);
