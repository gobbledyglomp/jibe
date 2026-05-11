package com.jibe.app.data.local

enum class FeatureId(val key: String) {
    CLIPBOARD("clipboard"),
    NOTIFICATIONS("notifications"),
    FILE_TRANSFER("file_transfer"),
    PRESENTATION("presentation"),
    FIND_PHONE("find_phone"),
    PING("ping");

    companion object {
        val DEFAULT_ORDER: List<FeatureId> = entries.toList()

        fun fromKey(key: String): FeatureId? = entries.find { it.key == key }

        fun parseOrder(raw: String): List<FeatureId> {
            val parsed = raw.split(",").mapNotNull { fromKey(it.trim()) }
            val missing = DEFAULT_ORDER.filter { it !in parsed }
            return parsed + missing
        }

        fun serializeOrder(order: List<FeatureId>): String =
                order.joinToString(",") { it.key }
    }
}

/**
 * Given a full order and a reordered visible subset, produce the new full order.
 *
 * Non-visible items retain their relative positions; visible items are replaced
 * with the new order sequence.
 */
fun reorderSubset(
        fullOrder: List<FeatureId>,
        visibleIds: Set<FeatureId>,
        newVisibleOrder: List<FeatureId>,
): List<FeatureId> {
    val result = fullOrder.toMutableList()
    val visiblePositions = fullOrder.indices.filter { fullOrder[it] in visibleIds }
    newVisibleOrder.forEachIndexed { i, id ->
        if (i < visiblePositions.size) {
            result[visiblePositions[i]] = id
        }
    }
    return result
}
