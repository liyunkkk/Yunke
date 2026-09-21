package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Cancels both generation requests and output downloads when their child is stopped or times out. */
internal fun <T> executeGenerationRequest(
    client: OkHttpClient,
    request: Request,
    controller: AgentRunController?,
    read: (Response) -> T,
): T {
    controller?.throwIfCancelled()
    val call = client.newCall(request)
    val binding = controller?.register { call.cancel() }
    try {
        controller?.throwIfCancelled()
        return call.execute().use { response ->
            controller?.throwIfCancelled()
            val result = read(response)
            controller?.throwIfCancelled()
            result
        }
    } finally { binding?.close() }
}
