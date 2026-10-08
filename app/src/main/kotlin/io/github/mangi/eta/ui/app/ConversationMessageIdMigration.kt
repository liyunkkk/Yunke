package io.github.mangi.eta.ui.app

/**
 * Applies a legacy identity repair without replacing any existing message.
 *
 * The caller must hold the same database transaction for preflight and every rename.
 * A rejected plan publishes the original IDs and performs no writes, including when
 * a later target (or an undecodable stored row) blocks an otherwise valid first move.
 * Unexpected database failures propagate so the enclosing transaction rolls back.
 */
internal object ConversationMessageIdMigration {
    suspend fun <T> apply(
        original: List<T>,
        migrated: List<T>,
        storedRowIds: Set<String>,
        renames: List<Pair<String, String>>,
        targetExists: suspend (String) -> Boolean,
        rename: suspend (String, String) -> Int,
    ): List<T> {
        val moves = renames.filter { (oldId, newId) -> oldId != newId }
        if (moves.isEmpty()) return original

        val sources = moves.map { it.first }
        val targets = moves.map { it.second }
        if (sources.distinct().size != sources.size ||
            targets.distinct().size != targets.size ||
            sources.any { it !in storedRowIds } ||
            targets.any { it in storedRowIds }
        ) return original

        // EXISTS queries use the primary-key index and one parameter, regardless of
        // transcript length. Never interleave them with writes or skip just one move.
        for (target in targets) {
            if (targetExists(target)) return original
        }
        for ((oldId, newId) in moves) {
            check(rename(oldId, newId) == 1) {
                "Message identity migration changed an unexpected number of rows"
            }
        }
        return migrated
    }
}
