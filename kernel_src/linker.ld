ENTRY(_start)

SECTIONS
{
	. = 1M;
    _kernel_start = .;

	.text BLOCK(4K) : ALIGN(4K)
	{
		*(.text)
	}

	.rodata BLOCK(4K) : ALIGN(4K)
	{
		*(.rodata*)
	}

	.data BLOCK(4K) : ALIGN(4K)
	{
		*(.data)
	}

	.bss BLOCK(4K) : ALIGN(4K)
	{
		*(COMMON)
		*(.bss)
	}
    _kernel_end = .;

    /DISCARD/ :
    {
        *(.note*)
        *(.indent)
        *(.comment)
        *(.eh_frame)
        *(.eh_frame_hdr)
    }
}
