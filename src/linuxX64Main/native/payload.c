#include "payload.h"
// Freestanding, position independent code: no libc, dynamic loader or Lua
// library. These functions exist remotely only for this protected call, never
// in a saved closure.
__attribute__((section(".text.entry"), visibility("hidden"))) void
payload_entry(struct Payload *p) {
    if (p->activate) {
        void (*activate)(unsigned) = (void *)p->activate;
        activate(p->activate_count);
        return;
    }
    if (p->dlopen_function) {
        void *(*open_library)(const char *, int) = (void *)p->dlopen_function;
        void *(*find_symbol)(void *, const char *) = (void *)p->dlsym_function;
        void *handle = open_library(p->source, p->dlopen_flags);
        const struct FrDescriptor *(*prepare)(const struct FrConfig *) =
            handle ? find_symbol(handle, p->prepare_name) : 0;
        uintptr_t address = prepare ? (uintptr_t)prepare(&p->resident) : 0;
        for (size_t i = 0; i < sizeof address; ++i)
            p->result[i] = ((const char *)&address)[i];
        p->result_size = sizeof address;
        return;
    }
    if (p->query) {
        _Bool (*is_menu)(void *) = (void *)p->query;
        p->result[0] = is_menu((void *)p->state) ? '1' : '0';
        p->result_size = 1;
        return;
    }
    void *L = (void *)p->state;
    int (*load)(void *, void *, void *, const char *, const char *) =
        (void *)p->load;
    int (*pcall)(void *, int, int, int, int, void *) = (void *)p->pcall;
    const char *(*tostring)(void *, int, size_t *) = (void *)p->tostring;
    int (*type)(void *, int) = (void *)p->type;
    void (*settop)(void *, int) = (void *)p->settop;
    p->status = load(L, (void *)p->reader, p, p->name, p->mode);
    if (!p->status)
        p->status = pcall(L, 0, 1, 0, 0, 0);
    p->result_size = 0;
    if (type(L, -1) == FM_LUA_TSTRING) {
        size_t n = 0;
        const char *s = tostring(L, -1, &n);
        if (n >= FM_TEXT_CAP) {
            p->status = -2;
        } else {
            for (size_t i = 0; i < n; ++i)
                p->result[i] = s[i];
            p->result_size = n;
        }
    } else if (!p->status)
        p->status = -3;
    // lua_load/pcall leave exactly one result or error above the original
    // stack.
    settop(L, FM_LUA_POP_ONE);
}
__attribute__((section(".text.reader"), visibility("hidden"))) const char *
payload_reader(void *L, struct Payload *p, size_t *size) {
    (void)L;
    *size = p->length;
    p->length = 0;
    return *size ? p->source : 0;
}
