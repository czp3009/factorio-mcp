#include "debug_image.h"
#include "dwarf_format.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cxxabi.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <fmt/format.h>
#include <limits>
#include <set>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace factorio {
namespace {
// ELF gABI SHF_COMPRESSED, absent from the older Kotlin/Native sysroot headers.
constexpr Elf64_Xword compressedSectionFlag = 0x800;
[[noreturn]] void invalid(const std::string &s) {
    throw std::runtime_error(fmt::format("developer debug information: {}", s));
}
struct Cursor {
    const unsigned char *p, *end;
    uint64_t fixed(size_t n) {
        if (n > sizeof(uint64_t) || size_t(end - p) < n)
            invalid("truncated DWARF value");
        uint64_t v = 0;
        for (size_t i = 0; i < n; ++i)
            v |= uint64_t(*p++) << (i * 8);
        return v;
    }
    void skip(uint64_t n) {
        if (n > uint64_t(end - p))
            invalid("truncated DWARF block");
        p += n;
    }
    uint64_t leb() {
        uint64_t v = 0;
        for (unsigned shift = 0; shift < 64; shift += 7) {
            auto b = fixed(1);
            if (shift == 63 && (b & 0x7e))
                invalid("ULEB128 overflow");
            v |= (b & 0x7f) << shift;
            if (!(b & 0x80))
                return v;
        }
        invalid("invalid ULEB128");
    }
    int64_t sleb() {
        uint64_t v = 0;
        unsigned shift = 0;
        uint64_t b;
        do {
            if (shift >= 64)
                invalid("SLEB128 overflow");
            b = fixed(1);
            v |= (b & 0x7f) << shift;
            shift += 7;
        } while (b & 0x80);
        if (shift < 64 && (b & 0x40))
            v |= (~uint64_t(0)) << shift;
        return static_cast<int64_t>(v);
    }
};
struct Field {
    uint64_t attr, form;
    int64_t implicit;
};
struct Abbrev {
    uint64_t tag = 0;
    bool children = false;
    std::vector<Field> fields;
};
struct Value {
    uint64_t n = 0;
    bool address = false, indexed = false;
};
Value value(Cursor &c, uint64_t form, int64_t implicit, unsigned pointer_size,
            unsigned offset_size, unsigned version, unsigned depth = 0) {
    using namespace dwarf;
    if (depth > 8)
        invalid("recursive DW_FORM_indirect");
    switch (form) {
    case addr:
        return {c.fixed(pointer_size), true, false};
    case data1:
    case flag:
    case ref1:
    case strx1:
        return {c.fixed(1)};
    case data2:
    case ref2:
    case strx2:
        return {c.fixed(2)};
    case strx3:
        return {c.fixed(3)};
    case data4:
    case ref4:
    case ref_sup4:
    case strx4:
        return {c.fixed(4)};
    case data8:
    case ref8:
    case ref_sig8:
    case ref_sup8:
        return {c.fixed(8)};
    case strp:
    case sec_offset:
    case line_strp:
    case strp_sup:
    case gnu_ref_alt:
    case gnu_strp_alt:
        return {c.fixed(offset_size)};
    case ref_addr:
        return {c.fixed(version == 2 ? pointer_size : offset_size)};
    case udata:
    case ref_udata:
    case strx:
    case loclistx:
    case rnglistx:
    case gnu_str_index:
        return {c.leb()};
    case addrx:
    case gnu_addr_index:
        return {c.leb(), true, true};
    case addrx1:
        return {c.fixed(1), true, true};
    case addrx2:
        return {c.fixed(2), true, true};
    case addrx3:
        return {c.fixed(3), true, true};
    case addrx4:
        return {c.fixed(4), true, true};
    case sdata:
        return {static_cast<uint64_t>(c.sleb())};
    case implicit_const:
        return {static_cast<uint64_t>(implicit)};
    case flag_present:
        return {1};
    case indirect:
        return value(c, c.leb(), 0, pointer_size, offset_size, version,
                     depth + 1);
    case block1: {
        auto n = c.fixed(1);
        c.skip(n);
        break;
    }
    case block2: {
        auto n = c.fixed(2);
        c.skip(n);
        break;
    }
    case block4: {
        auto n = c.fixed(4);
        c.skip(n);
        break;
    }
    case block:
    case exprloc: {
        auto n = c.leb();
        c.skip(n);
        break;
    }
    case data16:
        c.skip(16);
        break;
    case string:
        while (c.fixed(1)) {
        }
        break;
    default:
        invalid(fmt::format("unsupported DWARF form {}", form));
    }
    return {};
}
std::string checked_string(const unsigned char *data, size_t size,
                           size_t offset) {
    if (offset >= size)
        invalid("string offset outside section");
    auto end = static_cast<const unsigned char *>(
        memchr(data + offset, 0, size - offset));
    if (!end)
        invalid("unterminated symbol name");
    return {reinterpret_cast<const char *>(data + offset),
            size_t(end - (data + offset))};
}
} // namespace
DebugImage::DebugImage(const std::string &path,
                       std::vector<std::string> additional_functions)
    : additional_functions_(std::move(additional_functions)) {
    fd_ = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd_ < 0)
        invalid(fmt::format("cannot open {}", path));
    struct stat st{};
    if (fstat(fd_, &st) ||
        st.st_size < static_cast<off_t>(sizeof(Elf64_Ehdr))) {
        close(fd_);
        fd_ = -1;
        invalid("invalid ELF file");
    }
    size_ = st.st_size;
    void *mapped = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
    if (mapped == MAP_FAILED) {
        close(fd_);
        fd_ = -1;
        invalid("cannot map ELF");
    }
    data_ = static_cast<const unsigned char *>(mapped);
    try {
        parse();
        validate_dwarf();
    } catch (...) {
        munmap(const_cast<unsigned char *>(data_), size_);
        close(fd_);
        throw;
    }
}
DebugImage::~DebugImage() {
    if (data_)
        munmap(const_cast<unsigned char *>(data_), size_);
    if (fd_ >= 0)
        close(fd_);
}
const unsigned char *DebugImage::range(uint64_t offset, uint64_t length) const {
    if (offset > size_ || length > size_ - offset)
        invalid("ELF range outside file");
    return data_ + offset;
}
const Function &DebugImage::function(const std::string &name) const {
    auto it = functions_.find(name);
    if (it == functions_.end())
        invalid(fmt::format("missing function {}", name));
    return it->second;
}
const unsigned char *DebugImage::bytes(const Function &f) const {
    return range(f.file_offset, f.size);
}
void DebugImage::parse() {
    Elf64_Ehdr eh;
    memcpy(&eh, range(0, sizeof eh), sizeof eh);
    if (memcmp(eh.e_ident, ELFMAG, SELFMAG) ||
        eh.e_ident[EI_CLASS] != ELFCLASS64 ||
        eh.e_ident[EI_DATA] != ELFDATA2LSB || eh.e_machine != EM_X86_64 ||
        (eh.e_type != ET_DYN && eh.e_type != ET_EXEC))
        invalid("expected Linux x86_64 ELF");
    if (eh.e_shentsize != sizeof(Elf64_Shdr) ||
        eh.e_phentsize != sizeof(Elf64_Phdr))
        invalid("invalid ELF table entry sizes");
    Elf64_Shdr first{};
    memcpy(&first, range(eh.e_shoff, sizeof first), sizeof first);
    size_t section_count = eh.e_shnum ? eh.e_shnum : first.sh_size;
    size_t names_index =
        eh.e_shstrndx == SHN_XINDEX ? first.sh_link : eh.e_shstrndx;
    if (section_count > size_ / sizeof(Elf64_Shdr) ||
        names_index >= section_count)
        invalid("invalid section table");
    std::vector<Elf64_Shdr> headers(section_count);
    memcpy(headers.data(),
           range(eh.e_shoff, headers.size() * sizeof(Elf64_Shdr)),
           headers.size() * sizeof(Elf64_Shdr));
    auto names = headers[names_index];
    auto strings = range(names.sh_offset, names.sh_size);
    for (auto &s : headers) {
        auto name = checked_string(strings, names.sh_size, s.sh_name);
        sections_[name] = {s.sh_offset, s.sh_size, s.sh_addr,   s.sh_flags,
                           s.sh_type,   s.sh_link, s.sh_entsize};
        if (s.sh_type == SHT_NOTE) {
            Cursor c{range(s.sh_offset, s.sh_size),
                     range(s.sh_offset, s.sh_size) + s.sh_size};
            while (size_t(c.end - c.p) >= sizeof(Elf64_Nhdr)) {
                size_t ns = c.fixed(4), ds = c.fixed(4);
                auto type = c.fixed(4);
                const auto *owner = c.p;
                c.skip((ns + 3) & ~size_t(3));
                const auto *desc = c.p;
                c.skip((ds + 3) & ~size_t(3));
                if (type == NT_GNU_BUILD_ID && ns == 4 &&
                    !memcmp(owner, "GNU", 4)) {
                    static const char hex[] = "0123456789abcdef";
                    for (size_t i = 0; i < ds; ++i) {
                        build_id += hex[desc[i] >> 4];
                        build_id += hex[desc[i] & 15];
                    }
                }
            }
        }
    }
    bool load = false;
    for (unsigned i = 0; i < eh.e_phnum; ++i) {
        Elf64_Phdr ph;
        memcpy(&ph, range(eh.e_phoff + i * sizeof ph, sizeof ph), sizeof ph);
        if (ph.p_type == PT_LOAD && ph.p_offset == 0) {
            first_load_address = ph.p_vaddr;
            load = true;
        }
    }
    if (!load)
        invalid("no ELF load segment covering file header");
    std::set<std::string> wanted = {game_update,     app_process,  app_in_menu,
                                    event_dispatch,  "lua_load",   "lua_pcallk",
                                    "lua_tolstring", "lua_settop", "lua_type"};
    wanted.insert(additional_functions_.begin(), additional_functions_.end());
    auto it = sections_.find(".symtab");
    if (it == sections_.end())
        invalid("developer ELF .symtab is missing");
    auto &table = it->second;
    if (table.entry_size != sizeof(Elf64_Sym) ||
        table.size % sizeof(Elf64_Sym) || table.link >= headers.size())
        invalid("invalid symbol table");
    auto names_section = headers[table.link];
    auto symbol_names = range(names_section.sh_offset, names_section.sh_size);
    for (uint64_t i = 0; i < table.size; i += sizeof(Elf64_Sym)) {
        Elf64_Sym s;
        memcpy(&s, range(table.offset + i, sizeof s), sizeof s);
        if (ELF64_ST_TYPE(s.st_info) != STT_FUNC || s.st_shndx == SHN_UNDEF ||
            s.st_shndx >= headers.size())
            continue;
        auto name =
            checked_string(symbol_names, names_section.sh_size, s.st_name);
        if (!wanted.count(name)) {
            if (name.compare(0, 2, "_Z"))
                continue;
            // Kotlin/Native supplies a fallback __cxa_demangle in the
            // executable. Resolve the standard C++ ABI implementation from the
            // system library.
            static auto demangle =
                reinterpret_cast<decltype(&abi::__cxa_demangle)>(
                    dlvsym(RTLD_NEXT, "__cxa_demangle", "CXXABI_1.3"));
            if (!demangle)
                invalid("system C++ ABI demangler is unavailable");
            int status = 0;
            char *demangled = demangle(name.c_str(), nullptr, nullptr, &status);
            if (demangled) {
                name = demangled;
                free(demangled);
            }
            if (!wanted.count(name))
                continue;
        }
        auto sh = headers[s.st_shndx];
        if (!s.st_size && name.size() >= 4 &&
            name.compare(name.size() - 4, 4, "$plt") == 0) {
            // Linker-generated PLT symbols have no DWARF body and may have a
            // zero symbol size. Derive their extent from the actual ELF
            // symbols.
            uint64_t end = sh.sh_addr + sh.sh_size;
            for (uint64_t j = 0; j < table.size; j += sizeof(Elf64_Sym)) {
                Elf64_Sym next;
                memcpy(&next, range(table.offset + j, sizeof next),
                       sizeof next);
                if (next.st_shndx == s.st_shndx && next.st_value > s.st_value &&
                    next.st_value < end)
                    end = next.st_value;
            }
            if (end > s.st_value)
                s.st_size = end - s.st_value;
        }
        if (!(sh.sh_flags & SHF_EXECINSTR) || s.st_value < sh.sh_addr ||
            !s.st_size || s.st_value - sh.sh_addr > sh.sh_size ||
            s.st_size > sh.sh_size - (s.st_value - sh.sh_addr))
            invalid(fmt::format("invalid code range for {}", name));
        Function f{name, s.st_value, s.st_size,
                   sh.sh_offset + s.st_value - sh.sh_addr, false};
        auto old = functions_.find(name);
        if (old != functions_.end() && old->second.address != f.address)
            invalid(fmt::format("ambiguous symbol {}", name));
        functions_[name] = f;
    }
    for (auto &name : wanted)
        function(name);
}
void DebugImage::validate_dwarf() {
    auto info = sections_.find(".debug_info"),
         abbrev = sections_.find(".debug_abbrev");
    if (info == sections_.end() || abbrev == sections_.end())
        invalid("developer DWARF sections are missing; no offset fallback");
    if ((info->second.flags | abbrev->second.flags) & compressedSectionFlag)
        invalid("compressed DWARF is not supported");
    auto &is = info->second;
    auto &as = abbrev->second;
    Cursor all{range(is.offset, is.size), range(is.offset, is.size) + is.size};
    std::map<uint64_t, std::vector<Abbrev>> tables;
    std::map<uint64_t, Function *> addresses;
    for (auto &[name, f] : functions_)
        addresses[f.address] = &f;
    while (all.p < all.end) {
        auto length = all.fixed(4);
        unsigned offset_size = 4;
        if (length == std::numeric_limits<uint32_t>::max()) {
            length = all.fixed(8);
            offset_size = 8;
        }
        if (length > uint64_t(all.end - all.p))
            invalid("truncated compilation unit");
        Cursor cu{all.p, all.p + length};
        all.skip(length);
        unsigned version = cu.fixed(2), pointer_size;
        uint64_t abbrev_offset;
        if (version >= 2 && version <= 4) {
            abbrev_offset = cu.fixed(offset_size);
            pointer_size = cu.fixed(1);
        } else if (version == 5) {
            auto kind = cu.fixed(1);
            pointer_size = cu.fixed(1);
            abbrev_offset = cu.fixed(offset_size);
            if (kind != 1)
                continue; // Only full compile units supply this demo's function
                          // definitions.
        } else
            invalid(fmt::format("unsupported DWARF version {}", version));
        if (pointer_size != sizeof(uintptr_t))
            invalid("DWARF pointer size differs from target ABI");
        if (!tables.count(abbrev_offset)) {
            if (abbrev_offset >= as.size)
                invalid("invalid abbreviation offset");
            Cursor c{range(as.offset + abbrev_offset, as.size - abbrev_offset),
                     range(as.offset, as.size) + as.size};
            std::vector<Abbrev> table;
            while (true) {
                auto code = c.leb();
                if (!code)
                    break;
                if (code > as.size)
                    invalid("invalid abbreviation code");
                if (table.size() <= code)
                    table.resize(code + 1);
                auto &a = table[code];
                a.tag = c.leb();
                a.children = c.fixed(1) != 0;
                while (true) {
                    auto attr = c.leb(), form = c.leb();
                    if (!attr && !form)
                        break;
                    a.fields.push_back(
                        {attr, form,
                         form == dwarf::implicit_const ? c.sleb() : 0});
                }
            }
            tables.emplace(abbrev_offset, std::move(table));
        }
        auto &table = tables.at(abbrev_offset);
        uint64_t address_base = 0;
        while (cu.p < cu.end) {
            auto code = cu.leb();
            if (!code)
                continue;
            if (code >= table.size() || !table[code].tag)
                invalid("undefined abbreviation");
            auto &a = table[code];
            Value low{}, high{};
            bool has_low = false, has_high = false;
            for (auto &field : a.fields) {
                auto v = value(cu, field.form, field.implicit, pointer_size,
                               offset_size, version);
                if (field.attr == dwarf::low_pc) {
                    low = v;
                    has_low = true;
                }
                if (field.attr == dwarf::high_pc) {
                    high = v;
                    has_high = true;
                }
                if (field.attr == dwarf::addr_base)
                    address_base = v.n;
            }
            if (a.tag != dwarf::subprogram || !has_low || !has_high)
                continue;
            auto resolve = [&](Value v) {
                if (!v.indexed)
                    return v.n;
                auto ad = sections_.find(".debug_addr");
                if (ad == sections_.end())
                    invalid("missing .debug_addr");
                if (v.n > (ad->second.size / pointer_size) ||
                    address_base > ad->second.size ||
                    v.n * pointer_size > ad->second.size - address_base ||
                    pointer_size >
                        ad->second.size - address_base - v.n * pointer_size)
                    invalid("invalid address index");
                Cursor c{
                    range(ad->second.offset + address_base + v.n * pointer_size,
                          pointer_size),
                    nullptr};
                c.end = c.p + pointer_size;
                return c.fixed(pointer_size);
            };
            uint64_t begin = resolve(low),
                     end = high.address ? resolve(high) : begin + high.n;
            auto f = addresses.find(begin);
            if (f != addresses.end() && end > begin &&
                end - begin >= f->second->size)
                f->second->dwarf = true;
        }
    }
    // Lua helpers may have only symbol records after LTO. Game entry points
    // must also have actual DWARF subprogram address ranges.
    for (auto name : {game_update, app_process, app_in_menu, event_dispatch})
        if (!function(name).dwarf)
            invalid(fmt::format("missing DWARF code range for {}", name));
}
} // namespace factorio
