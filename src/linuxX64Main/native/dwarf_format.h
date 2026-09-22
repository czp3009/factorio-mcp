#pragma once
#include <cstdint>
// DWARF standard encodings, not target-specific addresses or object layouts.
// https://dwarfstd.org/doc/DWARF5.pdf, sections 7.5 and 7.6.
namespace dwarf {
enum Tag : uint64_t { subprogram=0x2e };
enum Attribute : uint64_t { low_pc=0x11, high_pc=0x12, addr_base=0x73 };
enum Form : uint64_t {
    addr=0x01, block2=0x03, block4=0x04, data2=0x05, data4=0x06,
    data8=0x07, string=0x08, block=0x09, block1=0x0a, data1=0x0b,
    flag=0x0c, sdata=0x0d, strp=0x0e, udata=0x0f, ref_addr=0x10,
    ref1=0x11, ref2=0x12, ref4=0x13, ref8=0x14, ref_udata=0x15,
    indirect=0x16, sec_offset=0x17, exprloc=0x18, flag_present=0x19,
    strx=0x1a, addrx=0x1b, ref_sup4=0x1c, strp_sup=0x1d, data16=0x1e,
    line_strp=0x1f, ref_sig8=0x20, implicit_const=0x21, loclistx=0x22,
    rnglistx=0x23, ref_sup8=0x24, strx1=0x25, strx2=0x26, strx3=0x27,
    strx4=0x28, addrx1=0x29, addrx2=0x2a, addrx3=0x2b, addrx4=0x2c,
    gnu_addr_index=0x1f01, gnu_str_index=0x1f02,
    gnu_ref_alt=0x1f20, gnu_strp_alt=0x1f21
};
}
