#pragma once
#include "bridge.h"
#include "observation_storage.h"
#include <dxgi.h>

void captureFrame(IDXGISwapChain *swapChain, FmResult &result, ObservationStorage &storage);
