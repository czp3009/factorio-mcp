#pragma once
#include "resident.h"
#ifdef __cplusplus
extern "C" {
#endif
// Returned UTF-8 storage belongs to the bridge and lives until the next call.
struct FmDiagnosticStatus {
    int main_menu;
    const char *build_id;
};
const char *fm_status(int pid, int timeout_ms,
                      struct FmDiagnosticStatus *status);
const char *fm_eval(int pid, const char *source, int timeout_ms);
// Interrupt a pending rendezvous, never an executing game function.
void fm_cancel_wait(int requested);
const char *fm_error(void);
// Reuses an existing resident; installs the embedded library only when absent.
enum FmAttachPhase { FM_WAITING, FM_LOADING, FM_APP_AVAILABLE };
// A pending safe point returns -2 without installing code; failures return -1.
int fm_resident_open(int pid, const char *bootstrap, int timeout_ms, int *phase,
                     int install);
int fm_resident_enable(int pid, uint64_t descriptor, int timeout_ms);
int fm_lock(int pid);
void fm_unlock(int fd);
void fm_signals(void);
int fm_cancelled(void);
#ifdef __cplusplus
}
#endif
