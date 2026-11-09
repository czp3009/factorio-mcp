package com.hiczp.factorio.mcp

/** Byte ranges retain the game's original UTF-8 and reject offsets inside a character. */
internal val utf8SliceLua = """
    local function slice_utf8(value,offset,limit)
      local total=#value
      local start=math.min(offset,total)
      local function continuation(index)
        local byte=string.byte(value,index)
        return byte and byte>=128 and byte<192
      end
      assert(start==total or not continuation(start+1),"String offset is inside a UTF-8 character")
      local finish=math.min(total,start+limit)
      if finish<total then
        while finish>start and continuation(finish+1) do finish=finish-1 end
      end
      assert(finish>start or start==total,"String limit cannot contain the next UTF-8 character")
      return {value=string.sub(value,start+1,finish),value_type="string",unit="utf8_bytes",
        offset=offset,total=total,has_more=finish<total,truncated=finish<total,next_offset=finish<total and finish or nil}
    end
""".trimIndent()
