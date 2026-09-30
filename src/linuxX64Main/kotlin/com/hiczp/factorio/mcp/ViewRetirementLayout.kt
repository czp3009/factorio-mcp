package com.hiczp.factorio.mcp

/** A concrete primary-table retirement entry and the owning Game's verified normal destruction path.
 * Installing this entry still requires live evidence/protection checks and owned hook cleanup. A callback records
 * retirement intent before the native call; it does not dispatch a deletion or infer the object's gameplay role.
 */
internal data class ViewRetirementLayout(
    val type: ItaniumType,
    val method: ItaniumVtable.Method,
    val owner: VirtualMemberRetirement.Proof,
) {
    companion object {
        fun resolve(image: ElfImage, gameSize: Long, viewSize: Long, member: Long): ViewRetirementLayout {
            val type = ItaniumType.resolve(image, "8GameView")
            val viewClass = ItaniumClass.resolve(image, "8GameView")
            val base = ItaniumClass.resolve(image, "18GarbageCollectable")
            require(viewClass.directBase(base, viewSize, 8) == 0L) {
                "GameView retirement does not use its primary GarbageCollectable base"
            }
            val name = "_ZN18GarbageCollectable13flagForDeleteEv"
            val method = ItaniumVtable.resolve(image, "_ZTV8GameView").method(image, name)
            // A base with no standalone instances need not have an emitted vtable. Resolve the inherited
            // function in the concrete object's primary table, then prove this exact slot at the owner call.
            require(method.addressPoint == type.addressPoint)
            return ViewRetirementLayout(
                type, method,
                VirtualMemberRetirement.resolve(image, "_ZN4GameD2Ev", gameSize, member, method)
            )
        }
    }
}
