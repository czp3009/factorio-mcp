#define _GNU_SOURCE 1
#include "bridge.h"
#include <fmt/format.h>
#include "debug_image.h"
#include "resident_symbols.h"
#include <memory>
#include <asm/processor-flags.h>
#include <sys/debugreg.h>
#include "payload.h"
#include "payload_blob.h"
#include <sys/ptrace.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/mman.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <dirent.h>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <array>
#include <algorithm>
#include <atomic>
#include <dlfcn.h>
#include <sys/socket.h>
#include <sys/un.h>

extern "C" const unsigned char factorio_resident_start[], factorio_resident_end[];

namespace {
using U = uint64_t;
using Clock = std::chrono::steady_clock;
thread_local std::string result, error;
volatile sig_atomic_t cancelled = 0;
std::atomic_bool wait_cancelled{false};
void on_signal(int) { if (cancelled < 100) ++cancelled; }
[[noreturn]] void fail(const std::string &s) { throw std::runtime_error(s); }
std::string syserr(const char *s) { return fmt::format("{}: {}", s, strerror(errno)); }
long trace(enum __ptrace_request op, int tid, U addr = 0, U data = 0) {
    errno = 0;
    long r = ptrace(op, tid, reinterpret_cast<void *>(addr), reinterpret_cast<void *>(data));
    if (r == -1 && errno) fail(syserr("ptrace"));
    return r;
}
std::string proc(int pid, const char *part) { return fmt::format("/proc/{}/{}", pid, part); }
std::vector<int> tids(int pid) {
    DIR *d = opendir(proc(pid, "task").c_str());
    if (!d) fail(syserr("open /proc/PID/task"));
    std::vector<int> v;
    while (auto *e = readdir(d)) if (e->d_name[0] >= '0' && e->d_name[0] <= '9') v.push_back(atoi(e->d_name));
    closedir(d);
    return v;
}
void read_memory(int pid, U addr, void *data, size_t n) {
    iovec local{data, n}, remote{reinterpret_cast<void *>(addr), n};
    if (process_vm_readv(pid, &local, 1, &remote, 1, 0) != static_cast<ssize_t>(n))
        fail(syserr("process_vm_readv (invalid state or insufficient ptrace permission)"));
}
template<class T = U> T read(int pid, U a) { T v{}; read_memory(pid, a, &v, sizeof v); return v; }
void write_memory(int pid, U addr, const void *data, size_t n) {
    iovec local{const_cast<void *>(data), n}, remote{reinterpret_cast<void *>(addr), n};
    if (process_vm_writev(pid, &local, 1, &remote, 1, 0) != static_cast<ssize_t>(n)) fail(syserr("process_vm_writev"));
}
struct Binary {
    int pid;
    factorio::DebugImage image;
    U base = 0;
    bool unsafe = false;
    explicit Binary(int p): pid(p), image(proc(pid,"exe"), [] {
        std::vector<std::string> names(std::begin(resident_symbols), std::end(resident_symbols));
        names.insert(names.end(), {"dlopen$plt", "dlsym$plt", factorio::loading_render});
        return names;
    }()) {
        struct stat st{};
        if (stat(proc(pid,"exe").c_str(), &st)) fail(syserr("stat target executable"));
        std::ifstream maps(proc(pid,"maps")); std::string line; bool found = false;
        while (std::getline(maps,line)) {
            unsigned long long start, end, off, inode; char perms[5], dev[32];
            if (sscanf(line.c_str(),"%llx-%llx %4s %llx %31s %llu",&start,&end,perms,&off,dev,&inode) == 6 && inode == st.st_ino && off == 0) {
                base = start - image.first_load_address; found = true; break;
            }
        }
        if (!found) fail("cannot locate target ELF load bias (check process permission)");
    }
    U address(const char *name) const { return base+image.function(name).address; }
    void verify(const char *name) const {
        const auto &fn=image.function(name);
        size_t n=std::min<size_t>(fn.size,sizeof(long));
        std::array<unsigned char,sizeof(long)> live{};
        read_memory(pid,base+fn.address,live.data(),n);
        if(memcmp(live.data(),image.bytes(fn),n)) fail(std::string("live code differs from developer ELF: ")+name);
    }
};
std::map<int,std::unique_ptr<Binary>> binaries;
std::map<int,int> sessions;
Binary &target(int pid) {
    auto found=binaries.find(pid);
    if(found==binaries.end()) fail("target debug information must be parsed before attaching");
    if(found->second->unsafe) fail("previous native failure requires inspection; refusing further calls");
    return *found->second;
}
struct Thread {
    bool stopped = false, armed = false;
    int signal = 0;
    U dr0 = 0, dr1 = 0, dr6 = 0, dr7 = 0;
};
constexpr U dr_offset(int n) { return offsetof(struct user, u_debugreg) + n*sizeof(U); }
struct Trace {
    int pid;
    std::map<int, Thread> threads;
    std::map<int, int> pending_stops;
    explicit Trace(int p): pid(p) {
        try { stop_all(); }
        catch (...) { detach(); throw; }
    }
    ~Trace() { detach(); }
    void seize_new() {
        for (int tid : tids(pid)) if (!threads.count(tid)) {
            errno = 0;
            // Thread creation only: no fork/vfork tracing or child-process injection.
            // New threads can inherit a temporary debug register before detach.
            if (ptrace(PTRACE_SEIZE,tid,nullptr,reinterpret_cast<void *>(PTRACE_O_TRACECLONE)) == -1) {
                int attach_errno=errno;
                if (errno == ESRCH) continue;
                // A clone can already be auto-attached while its parent's event
                // is still queued. Consume that event before registering it.
                if (errno == EPERM) {
                    std::ifstream status(proc(tid,"status")); std::string line;
                    bool ours = false;
                    while (std::getline(status,line)) {
                        if (line.rfind("TracerPid:",0)==0) {
                            ours = atoi(line.c_str()+strlen("TracerPid:")) == ::syscall(SYS_gettid);
                        }
                    }
                    if (ours) continue;
                    // Linux also returns EPERM when the enumerated task has exited.
                    // Only ignore it after procfs confirms the thread disappeared;
                    // a live thread's permission failure must still be reported.
                    auto remaining = tids(pid);
                    if (std::find(remaining.begin(), remaining.end(), tid) == remaining.end()) continue;
                }
                errno=attach_errno;
                fail(syserr("PTRACE_SEIZE; attach requires same-user ptrace permission or CAP_SYS_PTRACE"));
            }
            threads.emplace(tid,Thread{});
        }
    }
    int event(int tid, int s) {
        auto it = threads.find(tid);
        if (it == threads.end()) { pending_stops[tid]=s; return -1; }
        if (WIFEXITED(s) || WIFSIGNALED(s)) { threads.erase(it); return -1; }
        if (!WIFSTOPPED(s)) return -1;
        it->second.stopped = true;
        if (static_cast<unsigned>(s)>>16 == PTRACE_EVENT_CLONE) {
            unsigned long child = 0;
            trace(PTRACE_GETEVENTMSG,tid,0,reinterpret_cast<U>(&child));
            Thread newborn = it->second;
            newborn.stopped=false; newborn.signal=0;
            threads.emplace(child,newborn);
            int child_status = 0;
            auto pending=pending_stops.find(child);
            if(pending!=pending_stops.end()) {
                child_status=pending->second; pending_stops.erase(pending);
            } else {
                pid_t got;
                do { got=waitpid(child,&child_status,__WALL); } while(got==-1 && errno==EINTR);
                if(got!=static_cast<pid_t>(child)) fail(syserr("wait for cloned thread"));
            }
            event(child,child_status);
            // A newborn must inherit the parent's original debug state, not our
            // transient breakpoint. It has not executed user code yet.
            auto found=threads.find(child);
            if(found!=threads.end()) restore_debug(child,found->second);
            it->second.signal=0;
            return found==threads.end() ? -1 : static_cast<int>(child);
        }
        // PTRACE_INTERRUPT's synthetic event has no signal to redeliver.
        if (static_cast<unsigned>(s)>>16 == PTRACE_EVENT_STOP) return -1;
        it->second.signal = WSTOPSIG(s);
        return -1;
    }
    void stop_all() {
        for (;;) {
            seize_new();
            for (auto &[tid,t] : threads) if (!t.stopped) {
                errno = 0;
                if (ptrace(PTRACE_INTERRUPT,tid,nullptr,nullptr) == -1 && errno != ESRCH) fail(syserr("PTRACE_INTERRUPT"));
            }
            std::vector<int> running;
            for (auto &[tid,t] : threads) if (!t.stopped) running.push_back(tid);
            for (int tid : running) {
                int s = 0; pid_t got;
                do { got = waitpid(tid,&s,__WALL); } while (got == -1 && errno == EINTR);
                if (got == tid) event(tid,s); else if (errno == ECHILD) threads.erase(tid); else fail(syserr("waitpid"));
            }
            bool all = true;
            for (int tid : tids(pid)) if (!threads.count(tid)) all = false;
            if (all) break;
        }
        if (threads.empty()) fail("target exited");
    }
    void resume(int tid) {
        auto &t = threads.at(tid);
        trace(PTRACE_CONT,tid,0,t.signal); t.signal = 0; t.stopped = false;
    }
    void resume_others(int except) {
        for (auto &[tid,t] : threads) if (tid != except && t.stopped) resume(tid);
    }
    void restore_debug(int tid, Thread &t) {
        if (t.armed) {
            trace(PTRACE_POKEUSER,tid,dr_offset(DR_CONTROL),0);
            trace(PTRACE_POKEUSER,tid,dr_offset(DR_FIRSTADDR),t.dr0);
            trace(PTRACE_POKEUSER,tid,dr_offset(DR_FIRSTADDR+1),t.dr1);
            trace(PTRACE_POKEUSER,tid,dr_offset(DR_STATUS),t.dr6);
            trace(PTRACE_POKEUSER,tid,dr_offset(DR_CONTROL),t.dr7);
            t.armed = false;
        }
    }
    void disarm() {
        for (auto &[tid,t] : threads) restore_debug(tid,t);
    }
    // Successful commands must propagate cleanup failures, not merely log them.
    void finish() {
        stop_all();
        disarm();
        for (auto it=threads.begin(); it!=threads.end();) {
            auto &[tid,t]=*it;
            if (ptrace(PTRACE_DETACH,tid,nullptr,reinterpret_cast<void *>(static_cast<intptr_t>(t.signal))) == -1 && errno != ESRCH)
                fail(syserr("PTRACE_DETACH; cleanup incomplete"));
            it=threads.erase(it);
        }
    }
    void detach() noexcept {
        if (threads.empty()) return;
        try { stop_all(); disarm(); }
        catch (const std::exception &e) { fprintf(stderr,"cleanup: %s\n",e.what()); }
        for (auto &[tid,t] : threads) {
            if (ptrace(PTRACE_DETACH,tid,nullptr,reinterpret_cast<void *>(static_cast<intptr_t>(t.signal))) == -1 && errno != ESRCH)
                fprintf(stderr,"detach tid %d: %s\n",tid,strerror(errno));
        }
        threads.clear();
    }
    int rendezvous(U addr, int timeout, int only_tid = -1, U return_stack = 0, U alternate = 0, bool allow_pending = false) {
        const sig_atomic_t initial_cancelled = cancelled;
        if (wait_cancelled.load()) fail("operation cancelled before waiting for a game update");
        // Do not commandeer an existing debugger's hardware breakpoints.
        for (auto &[tid,t] : threads) {
            t.dr0 = trace(PTRACE_PEEKUSER,tid,dr_offset(DR_FIRSTADDR));
            t.dr1 = trace(PTRACE_PEEKUSER,tid,dr_offset(DR_FIRSTADDR+1));
            t.dr6 = trace(PTRACE_PEEKUSER,tid,dr_offset(DR_STATUS));
            t.dr7 = trace(PTRACE_PEEKUSER,tid,dr_offset(DR_CONTROL));
            if (t.dr7 & (DR_LOCAL_ENABLE_MASK|DR_GLOBAL_ENABLE_MASK))
                fail(fmt::format("target thread {} has active hardware breakpoints (DR7={})", tid, t.dr7));
        }
        for (auto &[tid,t] : threads) {
            if(only_tid!=-1 && tid!=only_tid) continue;
            t.armed = true;
            trace(PTRACE_POKEUSER,tid,dr_offset(DR_FIRSTADDR),addr);
            U control = (t.dr7 & ~(((U(1)<<DR_CONTROL_SIZE)-1)<<DR_CONTROL_SHIFT)) | (U(1)<<DR_LOCAL_ENABLE_SHIFT);
            if (alternate) {
                trace(PTRACE_POKEUSER,tid,dr_offset(DR_FIRSTADDR+1),alternate);
                control &= ~(((U(1)<<DR_CONTROL_SIZE)-1)<<(DR_CONTROL_SHIFT+DR_CONTROL_SIZE));
                control |= U(1)<<(DR_LOCAL_ENABLE_SHIFT+DR_ENABLE_SIZE);
            }
            trace(PTRACE_POKEUSER,tid,dr_offset(DR_CONTROL),control);
        }
        resume_others(-1);
        auto end = Clock::now()+std::chrono::milliseconds(timeout);
        for (;;) {
            int s = 0; int tid = waitpid(-1,&s,__WALL|WNOHANG);
            if (tid > 0) {
                int newborn=event(tid,s);
                if(newborn>0) resume(newborn);
                if (!threads.count(tid)) continue;
                auto &t = threads.at(tid);
                if (t.signal == SIGTRAP && t.armed) {
                    user_regs_struct regs{}; trace(PTRACE_GETREGS,tid,0,reinterpret_cast<U>(&regs));
                    U dr6 = trace(PTRACE_PEEKUSER,tid,dr_offset(DR_STATUS));
                    if ((regs.rip == addr && (dr6 & DR_TRAP0)) || (alternate && regs.rip == alternate && (dr6 & DR_TRAP1))) {
                        if(return_stack && regs.rsp!=return_stack) { t.signal=0; resume(tid); continue; }
                        t.signal = 0;
                        stop_all();
                        // Several threads can have hit our breakpoint before the stop completed.
                        for (auto &[other,x] : threads) if (x.signal == SIGTRAP && x.armed) {
                            user_regs_struct r{}; trace(PTRACE_GETREGS,other,0,reinterpret_cast<U>(&r));
                            if ((r.rip == addr || (alternate && r.rip == alternate)) &&
                                (trace(PTRACE_PEEKUSER,other,dr_offset(DR_STATUS)) & (DR_TRAP0|DR_TRAP1))) x.signal = 0;
                        }
                        disarm(); return tid;
                    }
                }
                resume(tid);
            } else if (tid < 0 && errno != EINTR) fail(syserr("wait for game tick"));
            if (cancelled != initial_cancelled) fail("interrupted while waiting for a game update");
            if (wait_cancelled.load()) fail("operation cancelled while waiting for a game update");
            if (Clock::now() >= end) {
                if (!allow_pending) fail("no active game update before timeout (menu, paused, loading or no player); no code executed");
                stop_all();
                for (auto &[other,x] : threads) if (x.signal==SIGTRAP && x.armed) {
                    auto r=regs(other);
                    if ((r.rip==addr || (alternate && r.rip==alternate)) &&
                        (trace(PTRACE_PEEKUSER,other,dr_offset(DR_STATUS)) & (DR_TRAP0|DR_TRAP1))) x.signal=0;
                }
                disarm();
                return 0;
            }
            usleep(1000);
        }
    }
    user_regs_struct regs(int tid) { user_regs_struct r{}; trace(PTRACE_GETREGS,tid,0,reinterpret_cast<U>(&r)); return r; }
    void setregs(int tid,const user_regs_struct &r) { trace(PTRACE_SETREGS,tid,0,reinterpret_cast<U>(&r)); }
    void wait_trap(int tid,U expected) {
        for (;;) {
            int s = 0; int got;
            do { got = waitpid(tid,&s,__WALL); } while (got == -1 && errno == EINTR);
            if (got != tid) fail(syserr("wait remote call"));
            int newborn=event(tid,s);
            if(newborn>0) resume(newborn);
            if (!threads.count(tid)) fail("target thread exited during remote call");
            auto &t = threads.at(tid);
            if (t.signal == SIGTRAP && regs(tid).rip == expected) { t.signal = 0; return; }
            // Preserve delivery of ordinary asynchronous signals while the call runs.
            // Synchronous faults cannot be repaired by unwinding an arbitrary game call.
            if (t.signal == SIGSEGV || t.signal == SIGBUS || t.signal == SIGILL || t.signal == SIGFPE || t.signal == SIGABRT)
                fail("remote call faulted; target cannot safely resume (see stderr)");
            if (t.signal == SIGTRAP) fail("unexpected SIGTRAP during remote call");
            resume(tid);
        }
    }
    U syscall(int tid,long nr,std::array<U,6> args) {
        auto saved = regs(tid); auto r = saved;
        U word = trace(PTRACE_PEEKTEXT,tid,saved.rip);
        constexpr size_t syscall_size=remote_syscall_end_offset-remote_syscall_offset;
        static_assert(syscall_size<=sizeof word);
        U patched=word;
        memcpy(&patched,payload_blob+remote_syscall_offset,syscall_size);
        trace(PTRACE_POKETEXT,tid,saved.rip,patched);
        r.rax = nr; r.orig_rax = -1ULL;
        r.rdi=args[0]; r.rsi=args[1]; r.rdx=args[2]; r.r10=args[3]; r.r8=args[4]; r.r9=args[5];
        r.eflags &= ~U(X86_EFLAGS_TF);
        try {
            setregs(tid,r); resume(tid); wait_trap(tid,saved.rip+syscall_size);
            U value = regs(tid).rax;
            trace(PTRACE_POKETEXT,tid,saved.rip,word); setregs(tid,saved);
            if (value >= U(-4095LL)) fail(fmt::format("remote syscall {}: {}", nr, strerror(-static_cast<long>(value))));
            return value;
        } catch (...) {
            if (threads.count(tid) && threads.at(tid).stopped) {
                trace(PTRACE_POKETEXT,tid,saved.rip,word); setregs(tid,saved);
            }
            throw;
        }
    }
};
// The stopped call supplies the actual lua_State* through the SysV argument
// register. No class offsets, Lua state layouts, or guessed heap pointers are read.
struct NativeCall { const FrConfig *resident=nullptr; U activate=0; unsigned activate_count=0; };
std::string evaluate_call(Trace &t, Binary &b, int tid, U lua_state, const char *source, U query = 0, NativeCall native = {}) {
    int pid=b.pid;
    auto saved=t.regs(tid);
    // x87/SSE/AVX/AVX-512 state must survive the temporary call, not only GPRs.
    std::vector<unsigned char> xstate(65536);
    iovec xs{xstate.data(),xstate.size()};
    trace(PTRACE_GETREGSET,tid,NT_X86_XSTATE,reinterpret_cast<U>(&xs));
    long page=sysconf(_SC_PAGESIZE);
    if(page<=0) fail("cannot determine system page size");
    const size_t page_size=page;
    auto align=[&](size_t n) { return ((n+page_size-1)/page_size)*page_size; };
    const size_t code_size=align(sizeof payload_blob), stack_size=align(1024*1024);
    size_t data_size=align(sizeof(Payload)+strlen(source)+1+(native.resident ? native.resident->script_length : 0));
    size_t total=code_size+data_size+page_size+stack_size;
    U arena=t.syscall(tid,SYS_mmap,{0,total,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,U(-1LL),0});
    bool running_call=false;
    try {
        write_memory(pid,arena,payload_blob,sizeof payload_blob);
        Payload p{};
        p.query=query; p.state=lua_state; p.load=b.address("lua_load"); p.pcall=b.address("lua_pcallk");
        p.tostring=b.address("lua_tolstring"); p.settop=b.address("lua_settop"); p.type=b.address("lua_type");
        p.reader=arena+payload_reader_offset; p.source=reinterpret_cast<const char *>(arena+code_size+sizeof p);
        p.length=strlen(source);
        p.activate = native.activate;
        p.activate_count = native.activate_count;
        if (native.resident) {
            p.dlopen_function=b.address("dlopen$plt"); p.dlsym_function=b.address("dlsym$plt"); p.dlopen_flags=RTLD_NOW|RTLD_LOCAL;
            p.resident=*native.resident;
            p.resident.script=p.source+p.length+1;
            strcpy(p.prepare_name,"factorio_resident_prepare_v1");
            write_memory(pid,reinterpret_cast<U>(p.resident.script),native.resident->script,native.resident->script_length);
        }
        strcpy(p.name,"=factorio-mcp"); strcpy(p.mode,"t");
        write_memory(pid,arena+code_size,&p,sizeof p);
        write_memory(pid,reinterpret_cast<U>(p.source),source,p.length+1);
        t.syscall(tid,SYS_mprotect,{arena,code_size,PROT_READ|PROT_EXEC,0,0,0});
        t.syscall(tid,SYS_mprotect,{arena+code_size+data_size,page_size,PROT_NONE,0,0,0});
        U return_address=arena+payload_trap_offset;
        U stack=arena+total-sizeof(U);
        write_memory(pid,stack,&return_address,sizeof return_address);
        auto call=saved; call.rip=arena+payload_entry_offset; call.rsp=stack; call.rdi=arena+code_size;
        call.orig_rax=-1ULL; call.eflags &= ~U(X86_EFLAGS_TF|X86_EFLAGS_RF); // clear TF/RF for ordinary execution
        t.setregs(tid,call);
        // Other threads may own allocator locks. Let them run during lua_load/pcall.
        t.resume_others(tid); running_call=true; t.resume(tid);
        t.wait_trap(tid,arena+payload_trap_end_offset);
        running_call=false;
        t.stop_all();
        trace(PTRACE_SETREGSET,tid,NT_X86_XSTATE,reinterpret_cast<U>(&xs));
        t.setregs(tid,saved);
        read_memory(pid,arena+code_size,&p,sizeof p);
        t.syscall(tid,SYS_munmap,{arena,total,0,0,0,0}); arena=0;
        if (p.result_size>=FM_TEXT_CAP) fail("invalid remote result size");
        std::string value(p.result,p.result_size);
        if (p.status) fail(fmt::format("Lua error {}: {}", p.status, value));
        return value;
    } catch (...) {
        if (running_call) {
            b.unsafe = true;
            // A native fault is outside the recoverable Lua error contract. Do not
            // pretend restoring RIP can undo an interrupted allocator/Lua operation.
            fprintf(stderr,"fatal: remote native call did not return normally; retaining arena and stopping target with SIGSTOP\n");
            if (t.threads.count(tid)) t.threads.at(tid).signal = SIGSTOP;
            kill(pid,SIGSTOP);
        } else if (arena) {
            try { t.stop_all(); trace(PTRACE_SETREGSET,tid,NT_X86_XSTATE,reinterpret_cast<U>(&xs)); t.setregs(tid,saved); t.syscall(tid,SYS_munmap,{arena,total,0,0,0,0}); }
            catch (const std::exception &e) { fprintf(stderr,"arena cleanup: %s\n",e.what()); }
        }
        throw;
    }
}
std::string evaluate(int pid,const char *source,int timeout) {
    if(!source || strlen(source)>FM_TEXT_CAP) fail("Lua source exceeds remote payload capacity");
    auto &b=target(pid);
    Trace t(pid);
    for(auto name:{factorio::game_update,factorio::event_dispatch,"lua_pcallk","lua_load","lua_tolstring","lua_settop","lua_type"}) b.verify(name);
    auto deadline=Clock::now()+std::chrono::milliseconds(timeout);
    auto remaining=[&] {
        auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(deadline-Clock::now()).count();
        if(ms<=0) fail("no suitable runtime Lua call before timeout; game may be paused or have no active script events");
        return static_cast<int>(ms);
    };
    int update=t.rendezvous(b.address(factorio::game_update),remaining());
    if(!t.regs(update).rsi) fail("main menu has no active game; no code executed");
    const auto &dispatcher=b.image.function(factorio::event_dispatch);
    for(;;) {
        int tid=t.rendezvous(b.address("lua_pcallk"),remaining());
        auto r=t.regs(tid);
        U caller=read(pid,r.rsp);
        if(caller<b.base+dispatcher.address || caller>=b.base+dispatcher.address+dispatcher.size) continue;
        // Wait for this very invocation to return. Its Lua state is still alive,
        // including when the handler just paused the game. Nested calls with the
        // same return address are distinguished by the original stack pointer.
        t.rendezvous(caller,remaining(),tid,r.rsp+sizeof(U));
        auto response=evaluate_call(t,b,tid,r.rdi,source);
        if(response=="unavailable-vm") continue;
        t.finish();
        return response;
    }
}
template<class F> const char *call(F f) {
    try { error.clear(); result=f(); return result.c_str(); }
    catch (const std::exception &e) { error=e.what(); return nullptr; }
}
}
extern "C" const char *fm_status(int pid,int timeout_ms,FmDiagnosticStatus *status) {
    return call([&] {
        auto &b=target(pid); Trace t(pid);
        b.verify(factorio::game_update);
        b.verify(factorio::app_process);
        b.verify(factorio::app_in_menu);
        int tid=t.rendezvous(b.address(factorio::game_update),timeout_ms,-1,0,b.address(factorio::app_process));
        auto regs=t.regs(tid);
        bool menu;
        if (regs.rip==b.address(factorio::app_process)) {
            menu=evaluate_call(t,b,tid,regs.rdi,"",b.address(factorio::app_in_menu))=="1";
        } else menu=!regs.rsi;
        status->main_menu=menu;
        status->build_id=b.image.build_id.c_str();
        t.finish();
        return std::string("ok");
    });
}
extern "C" const char *fm_eval(int pid,const char *source,int timeout_ms) {
    return call([&] { if (timeout_ms<1 || timeout_ms>600000) fail("timeout must be 1..600000 ms"); return evaluate(pid,source,timeout_ms); });
}
extern "C" void fm_cancel_wait(int requested) { wait_cancelled.store(requested != 0); }
extern "C" const char *fm_error() { return error.c_str(); }
namespace {
struct DescriptorFile {
    int value;
    ~DescriptorFile() { if (value >= 0) close(value); }
};
int connect_resident(int pid) {
    DescriptorFile socket_fd{socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0)};
    if (socket_fd.value < 0) fail(syserr("create resident connection"));
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    auto name = resident_socket_name(pid);
    if (name.size()+1 > sizeof address.sun_path) fail("resident IPC name is too long");
    memcpy(address.sun_path+1, name.data(), name.size());
    if (connect(socket_fd.value, reinterpret_cast<sockaddr *>(&address), offsetof(sockaddr_un,sun_path)+1+name.size())) {
        if (errno == ENOENT || errno == ECONNREFUSED) return -1;
        fail(syserr("connect to resident"));
    }
    ucred peer{}; socklen_t size=sizeof peer;
    if (getsockopt(socket_fd.value,SOL_SOCKET,SO_PEERCRED,&peer,&size) || peer.pid != pid || peer.uid != getuid())
        fail("resident IPC peer does not match the selected process");
    int result = socket_fd.value; socket_fd.value = -1;
    return result;
}
void write_code(int tid, U address, const unsigned char *bytes, size_t size) {
    for (size_t offset=0; offset<size; offset+=sizeof(long)) {
        U word=trace(PTRACE_PEEKTEXT,tid,address+offset);
        memcpy(&word,bytes+offset,std::min(sizeof word,size-offset));
        trace(PTRACE_POKETEXT,tid,address+offset,word);
    }
}
int attach_point(Binary &b, Trace &tracer, int timeout, int &tid) {
    b.verify(factorio::app_process);
    b.verify(factorio::loading_render);
    tid=tracer.rendezvous(b.address(factorio::app_process),timeout,-1,0,b.address(factorio::loading_render),true);
    if (!tid) return FM_WAITING;
    return tracer.regs(tid).rip==b.address(factorio::loading_render) ? FM_LOADING : FM_APP_AVAILABLE;
}
void activate_resident(Binary &b, Trace &tracer, int tid, U remote_descriptor, unsigned count) {
    const auto &names=resident_symbols;
    FrDescriptor prepared=read<FrDescriptor>(b.pid,remote_descriptor);
    prepared.error[sizeof prepared.error-1]=0;
    if (prepared.error[0]) fail(prepared.error);
    if (count>FR_HOOK_COUNT || prepared.installed>count) fail("invalid resident activation phase");
    if (prepared.version!=FR_VERSION || prepared.count!=FR_HOOK_COUNT || !prepared.activate) fail("invalid resident descriptor");
    for (unsigned i=0;i<count;++i) {
        auto &patch=prepared.patches[i];
        const auto &symbol=b.image.function(names[i]);
        if (patch.address!=b.address(names[i]) || !patch.length || patch.length>sizeof patch.original || patch.length>symbol.size || !patch.trampoline)
            fail("invalid resident patch range");
        std::array<unsigned char,64> live{}; read_memory(b.pid,patch.address,live.data(),patch.length);
        if (memcmp(live.data(),i<prepared.installed ? patch.replacement : patch.original,patch.length) || memcmp(patch.original,b.image.bytes(symbol),patch.length))
            fail("game entry changed while preparing resident hooks");
    }
    std::map<int,user_regs_struct> relocated;
    for (auto &[thread,state]:tracer.threads) {
        auto registers=tracer.regs(thread);
        for (unsigned i=prepared.installed;i<count;++i) {
            auto &patch=prepared.patches[i];
            if (registers.rip>patch.address && registers.rip<patch.address+patch.length) {
                auto offset=registers.rip-patch.address;
                if (!(patch.instruction_starts & (U(1)<<offset))) fail("thread stopped inside an undecodable instruction");
                relocated[thread]=registers;
            }
        }
    }
    const unsigned first=prepared.installed;
    unsigned installed=first;
    try {
        for (unsigned i=first;i<count;++i) {
            auto &patch=prepared.patches[i];
            ++installed; // Roll back this range too if a word write fails.
            write_code(tid,patch.address,patch.replacement,patch.length);
        }
        for (auto &[thread,saved]:relocated) {
            auto registers=saved;
            for (auto &patch:prepared.patches) if (saved.rip>patch.address && saved.rip<patch.address+patch.length)
                registers.rip=patch.trampoline+saved.rip-patch.address;
            tracer.setregs(thread,registers);
        }
        NativeCall activate; activate.activate=prepared.activate; activate.activate_count=count;
        evaluate_call(tracer,b,tid,0,"",0,activate);
        tracer.finish();
    } catch (...) {
        if (!b.unsafe) {
            for (unsigned i=first;i<installed;++i) {
                auto &patch=prepared.patches[i]; write_code(tid,patch.address,patch.original,patch.length);
            }
            for (auto &[thread,saved]:relocated) tracer.setregs(thread,saved);
        }
        throw;
    }
}
bool install_resident(Binary &b, const char *script, int timeout, int &phase) {
    const auto &names = resident_symbols;
    FrConfig config{}; config.version=FR_VERSION; config.script=script; config.script_length=strlen(script);
    for (unsigned i=0; i<FR_FUNCTION_COUNT; ++i) {
        auto &symbol=b.image.function(names[i]);
        config.functions[i]={b.address(names[i]),static_cast<size_t>(symbol.size)};
        b.verify(names[i]);
    }
    DescriptorFile library{static_cast<int>(::syscall(SYS_memfd_create,"factorio-mcp-resident",MFD_CLOEXEC))};
    if (library.value<0) fail(syserr("create embedded resident file"));
    auto size=static_cast<size_t>(factorio_resident_end-factorio_resident_start);
    for (size_t offset=0; offset<size;) {
        auto count=write(library.value,factorio_resident_start+offset,size-offset);
        if (count<0 && errno==EINTR) continue;
        if (count<=0) fail(syserr("write embedded resident"));
        offset+=count;
    }
    char path[128]; snprintf(path,sizeof path,"/proc/%d/fd/%d",getpid(),library.value);
    Trace tracer(b.pid);
    int tid=0;
    phase=attach_point(b,tracer,timeout,tid);
    if (phase!=FM_APP_AVAILABLE) { tracer.finish(); return false; }
    NativeCall load; load.resident=&config;
    auto value=evaluate_call(tracer,b,tid,0,path,0,load);
    U remote_descriptor=0;
    if (value.size()!=sizeof remote_descriptor) fail("invalid resident preparation response");
    memcpy(&remote_descriptor,value.data(),sizeof remote_descriptor);
    if (!remote_descriptor) fail("target could not load the embedded resident library");
    activate_resident(b,tracer,tid,remote_descriptor,FR_OBSERVER_HOOK_COUNT);
    return true;
}
}
extern "C" int fm_resident_enable(int pid,uint64_t descriptor,int timeout_ms) {
    try {
        error.clear();
        auto &b=target(pid);
        Trace tracer(pid);
        int tid=tracer.rendezvous(b.address(factorio::app_process),timeout_ms,-1,0,0,true);
        if (!tid) { tracer.finish(); return 0; }
        activate_resident(b,tracer,tid,descriptor,FR_HOOK_COUNT);
        return 1;
    } catch (const std::exception &exception) { error=exception.what(); return -1; }
}
extern "C" int fm_resident_open(int pid,const char *bootstrap,int timeout_ms,int *phase,int install) {
    try {
        error.clear();
        auto &binary=target(pid); // fm_lock has already parsed the developer ELF/DWARF.
        if (!bootstrap || strlen(bootstrap)>FR_MAX_REQUEST) fail("resident bootstrap exceeds protocol capacity");
        int connected=connect_resident(pid);
        if (connected>=0) return connected;
        if (!install) return -3;
        if (!install_resident(binary,bootstrap,timeout_ms,*phase)) return -2;
        connected=connect_resident(pid);
        if (connected<0) fail("resident listener is unavailable after installation");
        return connected;
    } catch (const std::exception &exception) { error=exception.what(); return -1; }
}
extern "C" int fm_lock(int pid) {
    std::string path=fmt::format("/tmp/factorio-mcp-{}-{}.lock", getuid(), pid);
    int fd=open(path.c_str(),O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
    if (fd<0) { error=syserr("open session lock"); return -1; }
    struct stat st{};
    if (fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_uid!=getuid() || flock(fd,LOCK_EX|LOCK_NB)) {
        error="another injector owns the target session, or lock file is invalid"; close(fd); return -1;
    }
    try { binaries[pid]=std::make_unique<Binary>(pid); sessions[fd]=pid; }
    catch(const std::exception &e) { error=e.what(); close(fd); return -2; }
    return fd;
}
extern "C" void fm_unlock(int fd) {
    auto found=sessions.find(fd);
    if (found!=sessions.end()) { binaries.erase(found->second); sessions.erase(found); }
    if (fd>=0) close(fd);
}
extern "C" void fm_signals() {
    struct sigaction action{}; action.sa_handler=on_signal; sigemptyset(&action.sa_mask);
    sigaction(SIGINT,&action,nullptr); sigaction(SIGTERM,&action,nullptr);
}
extern "C" int fm_cancelled() { return cancelled; }
