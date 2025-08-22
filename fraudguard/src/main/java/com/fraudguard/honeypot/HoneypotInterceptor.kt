package com.fraudguard.honeypot

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody

class HoneypotInterceptor(
    private val honeypotPaths: Set<String> = setOf("/api/secret/admin", "/api/debug/flags"),
    private val shortCircuit: Boolean = true,
    private val statusCode: Int = 403
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val hit = honeypotPaths.any { url.contains(it, ignoreCase = true) }
        if (!hit) return chain.proceed(request)

        if (!shortCircuit) {
            // Proceed but add a header to signal honeypot hit for upstream logging.
            val proceedReq = request.newBuilder()
                .addHeader("X-FraudGuard-Honeypot", "hit")
                .build()
            return chain.proceed(proceedReq)
        }

        val body = ResponseBody.create("application/json".toMediaType(), "{\"error\":\"forbidden\"}")
        return Response.Builder()
            .code(statusCode)
            .message("Forbidden")
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .body(body)
            .build()
    }
}