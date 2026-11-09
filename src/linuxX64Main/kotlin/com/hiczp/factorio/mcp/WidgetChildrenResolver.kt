package com.hiczp.factorio.mcp

internal fun WidgetChildren.resolve(image: ElfImage, symbol: String, objectSize: Long): List<WidgetChildren.Range> {
    val function = image.symbol(symbol)
    require(function.size in 1..4096) { "Recursive widget visitor exceeds analysis bound" }
    EhFrames(image).function(function)
    val badVisitor = image.symbol("_ZSt25__throw_bad_function_callv")
    image.functionBytes(badVisitor, 1)
    return analyze(image.functionBytes(function, 4096), function.address, badVisitor.address, objectSize)
}
