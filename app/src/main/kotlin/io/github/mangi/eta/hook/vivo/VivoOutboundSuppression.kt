package io.github.mangi.eta.hook.vivo

/** Once a mapper receipt identifies an owned dispatch, even error handling cannot call vendor. */
internal fun suppressOwnedVivoOutbound(
    owned: Boolean,
    onOwned: () -> Unit,
    onFailure: (Throwable) -> Unit,
    onDiagnostic: () -> Unit,
    proceed: () -> Any?,
): Any? {
    runCatching { onDiagnostic() }
    // Keep the original call outside failure handling: return and exception are unmodified.
    if (!owned) return proceed()
    runCatching { onOwned() }.onFailure { error ->
        runCatching { onFailure(error) }
    }
    return null
}
