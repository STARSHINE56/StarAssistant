/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.data.network

import com.yunx.app.util.DiagnosticLog
import okhttp3.Interceptor
import okhttp3.Response

/** Never copy login request/response bodies into diagnostic logs. */
class DiagnosticNetworkInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!DiagnosticLog.isEnabled()) return chain.proceed(chain.request())
        val request = chain.request()
        val start = System.currentTimeMillis()
        return try {
            val response = chain.proceed(request)
            DiagnosticLog.network(request.method, request.url.toString(), response.code,
                System.currentTimeMillis() - start,
                requestBody = request.body?.let { "长度=${runCatching { it.contentLength() }.getOrDefault(-1L)}，内容已隐藏" },
                responseBody = response.body?.let { "长度=${it.contentLength()}，内容已隐藏" })
            response
        } catch (e: Exception) {
            DiagnosticLog.network(request.method, request.url.toString(), -1,
                System.currentTimeMillis() - start, error = e.javaClass.simpleName)
            throw e
        }
    }
}
