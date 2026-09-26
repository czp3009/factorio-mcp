package com.hiczp.factorio.mcp

/** Confined to the connection dispatcher. Drain callers before closing the owner. */
internal class InputTaskOwner {
    private val tasks = mutableSetOf<GameInputTask>()

    fun retain(task: GameInputTask): GameInputTask {
        val owned =
            object : GameInputTask {
                override suspend fun awaitResult() = task.awaitResult()

                override suspend fun close(): InputSequenceResult {
                    val result = task.close()
                    tasks -= this
                    return result
                }
            }
        tasks += owned
        return owned
    }

    suspend fun close() {
        var failure: Exception? = null
        for (task in tasks.toList()) {
            try {
                task.close()
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
