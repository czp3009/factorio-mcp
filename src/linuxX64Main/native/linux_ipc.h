#pragma once
#include <stdint.h>

#ifdef __cplusplus
static_assert(__atomic_always_lock_free(sizeof(uint32_t), nullptr));
#else
_Static_assert(__atomic_always_lock_free(sizeof(uint32_t), 0), "IPC atomics must be lock-free");
#endif

static inline uint32_t fm_ipc_load(const uint32_t *value) {
    return __atomic_load_n(value, __ATOMIC_ACQUIRE);
}

static inline void fm_ipc_store(uint32_t *value, uint32_t next) {
    __atomic_store_n(value, next, __ATOMIC_RELEASE);
}

static inline int fm_ipc_exchange_if(uint32_t *value, uint32_t expected, uint32_t next) {
    return __atomic_compare_exchange_n(value, &expected, next, 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
}

static inline uint64_t fm_ipc_load64(const uint64_t *value) {
    return __atomic_load_n(value, __ATOMIC_ACQUIRE);
}

static inline void fm_ipc_store64(uint64_t *value, uint64_t next) {
    __atomic_store_n(value, next, __ATOMIC_RELEASE);
}
