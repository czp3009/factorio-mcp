#pragma once
#include <spawn.h>
#include <unistd.h>

extern char **environ;

// All Kotlin work stays in the parent. In particular, no managed branch or cinterop transition follows fork
// in a child of the multithreaded test runtime. Return the owned PID or a negative POSIX error code.
static inline int fm_fixture_spawn(const char *path, int incomingRead, int incomingWrite,
                                  int outgoingRead, int outgoingWrite) {
    posix_spawn_file_actions_t actions;
    int error = posix_spawn_file_actions_init(&actions);
    if (error)
        return -error;
    error = posix_spawn_file_actions_adddup2(&actions, incomingRead, STDIN_FILENO);
    if (!error)
        error = posix_spawn_file_actions_adddup2(&actions, outgoingWrite, STDOUT_FILENO);
    const int descriptors[] = {incomingRead, incomingWrite, outgoingRead, outgoingWrite};
    for (unsigned index = 0; index < 4 && !error; ++index)
        if (descriptors[index] != STDIN_FILENO && descriptors[index] != STDOUT_FILENO)
            error = posix_spawn_file_actions_addclose(&actions, descriptors[index]);
    pid_t pid = 0;
    char *const arguments[] = {(char *)path, NULL};
    if (!error)
        error = posix_spawn(&pid, path, &actions, NULL, arguments, environ);
    posix_spawn_file_actions_destroy(&actions);
    return error ? -error : pid;
}
