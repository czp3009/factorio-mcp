#pragma once
#include "bridge.h"

void readChat(const Symbols &symbols, void *game, FmChatSnapshot &result);
void sendChat(const Symbols &symbols, void *game, const FmChatRequest &request);
