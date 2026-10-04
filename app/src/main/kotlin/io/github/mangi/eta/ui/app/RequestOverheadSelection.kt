package io.github.mangi.eta.ui.app

/** Main-thread owned. Unknown estimates never become a successful zero or cross a binding. */
internal class RequestOverheadSelection {
    data class Binding(
        val owner: String,
        val providerId: String,
        val modelId: String,
        val assistantId: String,
        val modelGeneration: Long,
        val configurationGeneration: Long = 0,
    )
    data class Request(val sequence: Long, val binding: Binding)
    private data class Estimate(val binding: Binding, val tokens: Int)

    private var sequence = 0L
    private var latest: Request? = null
    private var estimate: Estimate? = null

    fun begin(binding: Binding): Request = Request(++sequence, binding).also { latest = it }

    fun tokensFor(binding: Binding): Int? = estimate?.takeIf { it.binding == binding }?.tokens

    fun complete(request: Request, current: Binding, tokens: Int?): Boolean {
        if (request != latest || request.binding != current || tokens == null || tokens < 0) return false
        estimate = Estimate(current, tokens)
        return true
    }
}
