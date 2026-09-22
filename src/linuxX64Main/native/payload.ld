SECTIONS {
  . = 0;
  .text : { *(.text.entry) *(.text.reader) *(.text*) *(.rodata*) }
  /DISCARD/ : { *(.eh_frame*) *(.comment) *(.note*) }
}
