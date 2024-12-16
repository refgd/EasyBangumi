package com.heyanle.easybangumi4.web

import android.graphics.Bitmap
import android.os.Build
import androidx.core.net.toUri
import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.utils.LogUtils
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.easybangumi4.utils.toJson
import com.heyanle.easybangumi4.web.controller.SourceController
import com.heyanle.easybangumi4.web.controller.VideoSourceController
import com.heyanle.easybangumi4.web.services.WebService
import com.heyanle.easybangumi4.web.utils.AssetsWeb
import com.heyanle.easybangumi4.web.utils.ReturnData
import com.hippo.unifile.UniFile
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL


class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")

    override fun serve(session: IHTTPSession): Response {
        WebService.serve()
        val startAt = System.currentTimeMillis()
        LogUtils.d(TAG, "${session.method.name} - ${session.uri} - ${session.queryParameterString} - Start($startAt)")

        return try {
            val ct = ContentType(session.headers["content-type"]).tryUTF8()
            session.headers["content-type"] = ct.contentTypeHeader

            when (session.method) {
                Method.OPTIONS -> handleOptions(session)
                Method.POST -> runBlocking { handlePost(session) }
                Method.GET -> handleGet(session)
                else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not supported")
            }.applyCORSHeaders(session.headers["origin"])
        } catch (e: Exception) {
            LogUtils.d(TAG, "${session.method.name} - ${session.uri} - Error End($startAt)\n${e.stackTraceToString()}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error: ${e.message}")
        }
    }

    private fun handleOptions(session: IHTTPSession): Response {
        return newFixedLengthResponse("").apply {
            addHeader("Access-Control-Allow-Methods", "POST")
            addHeader("Access-Control-Allow-Headers", "content-type")
            addHeader("Access-Control-Allow-Origin", session.headers["origin"])
        }
    }

    private suspend fun handlePost(session: IHTTPSession): Response {
        session.parseBody(HashMap<String, String>())
        val uri = session.uri

        val returnData = when (uri) {
            "/api/getMainTabs" -> VideoSourceController.getMainTabs(session.parameters)
            "/api/getSubTabs" -> VideoSourceController.getSubTabs(session.parameters)
            "/api/getPageContent" -> VideoSourceController.getContent(session.parameters)
            "/api/getDetailed" -> VideoSourceController.getDetailed(session.parameters)
            "/api/getPlayInfo" -> VideoSourceController.getPlayInfo(session.parameters)
            "/api/search" -> VideoSourceController.search(session.parameters)
            "/api/downCode" -> SourceController.downCode(session.parameters)
            else -> null
        }

        return returnData?.toResponse(session) ?: notFoundResponse()
    }

    private fun handleGet(session: IHTTPSession): Response {
        val uri = session.uri
        val parameters = session.parameters

        val returnData = when (uri) {
            "/api/getSources" -> VideoSourceController.sources
            "/stream/p/p/p/p/p/p/p/p/p/p/p/p" -> handleStream(parameters, session.headers["range"])
            else -> null
        }

        return returnData?.toResponse(session) ?: assetsWeb.getResponse(uri.ensureIndexHtml())
    }

    private fun handleStream(parameters: Map<String, List<String>>, rangeHeader: String?): ReturnData? {
        val url = parameters["url"]?.firstOrNull() ?: return null
        val returnData = ReturnData()

        if (url.startsWith("file://") ||  url.startsWith("content://")) {
            val uniFile = UniFile.fromUri(APP, url.toUri())
            if(uniFile == null) returnData.setData(notFoundResponse())
            else returnData.setData(uniFile)
        } else {
            val connection = URL(url).openConnection() as HttpURLConnection
            rangeHeader?.let {
                val rangeValue = it.replace("bytes=", "")
                connection.setRequestProperty("Range", "bytes=$rangeValue")
            }
            returnData.setData(connection)
        }

        return returnData
    }

    private fun String.ensureIndexHtml(): String {
        return if (endsWith("/")) this + "index.html" else this
    }

    private fun notFoundResponse(): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Resource not found")
    }

    private fun ReturnData.toResponse(session: IHTTPSession): Response {
        return when (data) {
            is Response -> data as Response
            is UniFile -> serveFile(data as UniFile, session)
            is HttpURLConnection -> serveHttpConnection(data as HttpURLConnection)
            is Bitmap -> serveBitmap(data as Bitmap)
            else -> newFixedLengthResponse(toJson())
        }
    }

    private fun serveFile(file: UniFile, session: IHTTPSession): Response {
        if (!file.exists() || !file.isFile) return notFoundResponse()

        val rangeHeader = session.headers["range"]
        return if (rangeHeader != null) {
            handleRangeRequest(file, rangeHeader)
        } else {
            val fis = file.openInputStream()
            newFixedLengthResponse(Response.Status.OK, "application/octet-stream", fis, file.length()).apply {
                addHeader("Content-Length", file.length().toString())
            }
        }
    }

    private fun handleRangeRequest(file: UniFile, rangeHeader: String): Response {
        val range = rangeHeader.replace("bytes=", "").split("-")
        val start = range[0].toLongOrNull() ?: 0
        val end = range.getOrNull(1)?.toLongOrNull() ?: (file.length() - 1)

        if (start > end || end >= file.length()) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid Range")
        }

        val contentLength = end - start + 1
        val fis = file.openInputStream().apply { skip(start) }

        return newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            "application/octet-stream",
            fis,
            contentLength
        ).apply {
            addHeader("Content-Range", "bytes $start-$end/${file.length()}")
            addHeader("Content-Length", contentLength.toString())
        }
    }

    private fun serveHttpConnection(connection: HttpURLConnection): Response {
        connection.connect()
        val contentType = connection.contentType
        val contentRange = connection.getHeaderField("Content-Range")
        val contentLength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connection.contentLengthLong
        } else {
            connection.contentLength.let { if (it > 0) it.toLong() else -1L }
        }

        return newChunkedResponse(
            if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL)
                Response.Status.PARTIAL_CONTENT
            else
                Response.Status.OK,
            contentType,
            BufferedInputStream(connection.inputStream)
        ).apply {
            contentRange?.let { addHeader("Content-Range", it) }
            addHeader("Accept-Ranges", "bytes")
            if (contentLength != -1L) {
                addHeader("Content-Length", contentLength.toString())
            }
        }
    }

    private fun serveBitmap(bitmap: Bitmap): Response {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        outputStream.close()

        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            ByteArrayInputStream(byteArray),
            byteArray.size.toLong()
        )
    }

    private fun Response.applyCORSHeaders(origin: String?): Response {
        addHeader("Access-Control-Allow-Methods", "GET, POST")
        addHeader("Access-Control-Allow-Origin", origin)
        return this
    }

    companion object {
        private const val TAG = "HttpServer"
    }

}
