@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.InputTransferLayout

/** Metadata for the queue read by the native cursor's blueprint import overlay. */
internal class InputTransferLayouts(types: DebugTypes) {
    private val client =
        types.pointerPath(
            "GlobalContext",
            "clientMultiplayerManager",
            "value",
            target = "ClientMultiplayerManager",
            indirections = 1,
        )
    private val synchronizer =
        types.pointerMember("ClientMultiplayerManager", "synchronizer", "ClientSynchronizer")
    private val listener =
        types.pointerMember("ClientSynchronizer", "networkInputListener", "NetworkInputListener")
    private val segmenter =
        types.namedMember(
            "NetworkInputListener",
            "inputActionSegmenter",
            "InputActionSegmenter",
            types.aggregateSize("InputActionSegmenter"),
        )
    private val queueType = types.memberTypeName("InputActionSegmenter", "segmentQueue")
    private val queue =
        segmenter +
                types.namedMember(
                    "InputActionSegmenter",
                    "segmentQueue",
                    queueType,
                    types.aggregateSize(queueType),
                )
    private val vector = "std::vector<InputActionSegment,std::allocator<InputActionSegment> >"
    private val map =
        types.pointerPath(
            queueType,
            "_Mypair",
            "_Myval2",
            "_Map",
            target = vector,
            indirections = 2,
        )
    private val mapSize =
        types.scalarPath(queueType, "_Mypair", "_Myval2", "_Mapsize", target = 7u, bytes = 8u)
    private val first =
        types.scalarPath(queueType, "_Mypair", "_Myval2", "_Myoff", target = 7u, bytes = 8u)
    private val count =
        types.scalarPath(queueType, "_Mypair", "_Myval2", "_Mysize", target = 7u, bytes = 8u)
    private val blockSize = types.enumValue(queueType, "_Block_size").also { check(it in 1u..64u) }
    private val vectorSize = types.aggregateSize(vector).also { check(it in 1uL..256uL) }.toUInt()
    private val vectorBegin =
        types.pointerPath(
            vector,
            "_Mypair",
            "_Myval2",
            "_Myfirst",
            target = "InputActionSegment",
            indirections = 1,
        )
    private val vectorEnd =
        types.pointerPath(
            vector,
            "_Mypair",
            "_Myval2",
            "_Mylast",
            target = "InputActionSegment",
            indirections = 1,
        )
    private val segmentSize =
        types.aggregateSize("InputActionSegment").also { check(it in 1uL..256uL) }.toUInt()
    private val actionType = types.namedMember("InputActionSegment", "type", "InputActionType", 2u)
    private val segmentIndex = types.scalarMember("InputActionSegment", "segmentNumber", 4u, 7u)
    private val totalSegments = types.scalarMember("InputActionSegment", "totalSegments", 4u, 7u)
    private val importAction =
        types.enumValue("InputActionType", "ImportBlueprintString").also { check(it <= 65535u) }

    fun write(target: InputTransferLayout) {
        target.client = client
        target.synchronizer = synchronizer
        target.listener = listener
        target.queue = queue
        target.map = map
        target.mapSize = mapSize
        target.first = first
        target.count = count
        target.blockSize = blockSize
        target.vectorSize = vectorSize
        target.vectorBegin = vectorBegin
        target.vectorEnd = vectorEnd
        target.segmentSize = segmentSize
        target.actionType = actionType
        target.segmentIndex = segmentIndex
        target.totalSegments = totalSegments
        target.importAction = importAction
        target.supported = 1u
    }
}
