package io.github.mangi.eta.ui

/**
 * Reuses one successful composition read for the immediate service notification.
 * Service identity is referential: replacements, failed reads and later callbacks
 * must refresh normally. No service or value is retained after the first callback.
 */
internal class InitialServicePreferenceSnapshot<S : Any, V : Any>(
    initialService: S?,
    initialValue: V?,
) {
    private var serviceSnapshot: S? = initialService
    private var valueSnapshot: V? = initialValue
    private var firstCallback = true

    /** Read only inside the UI's remembered initial state; never a long-lived copy. */
    fun initialValueForUi(): V? = valueSnapshot

    fun resolve(service: S?, refresh: (S?) -> V?): V? {
        val cached = valueSnapshot
        val reuse = firstCallback && service === serviceSnapshot && cached != null
        firstCallback = false
        serviceSnapshot = null
        valueSnapshot = null
        return if (reuse) cached else refresh(service)
    }
}
