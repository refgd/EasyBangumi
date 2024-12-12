package com.heyanle.easybangumi4.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import com.heyanle.easybangumi4.utils.LogUtils
import com.heyanle.easybangumi4.utils.coroutine.Coroutine
import com.heyanle.easybangumi4.utils.logi
import com.heyanle.easybangumi4.utils.stackTraceStr
import com.heyanle.easybangumi4.utils.toJson
import com.heyanle.easybangumi4.web.controller.SourceController
import com.heyanle.easybangumi4.web.controller.VideoSourceController
import com.heyanle.easybangumi4.web.services.WebService
import com.heyanle.easybangumi4.web.utils.AssetsWeb
import com.heyanle.easybangumi4.web.utils.ReturnData
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okio.Pipe
import okio.buffer
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL


class HttpServer(port: Int) : NanoHTTPD(port) {
    private val assetsWeb = AssetsWeb("web")

    @SuppressLint("NewApi")
    override fun serve(session: IHTTPSession): Response {
        WebService.serve()
        var returnData: ReturnData? = null
        val ct = ContentType(session.headers["content-type"]).tryUTF8()
        session.headers["content-type"] = ct.contentTypeHeader
        var uri = session.uri

        val startAt = System.currentTimeMillis()
        LogUtils.d(TAG, "${session.method.name} - $uri - ${session.queryParameterString} - Start($startAt)")

        try {
            when (session.method) {
                Method.OPTIONS -> {
                    val response = newFixedLengthResponse("")
                    response.addHeader("Access-Control-Allow-Methods", "POST")
                    response.addHeader("Access-Control-Allow-Headers", "content-type")
                    response.addHeader("Access-Control-Allow-Origin", "*")
//                    response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
                    //response.addHeader("Access-Control-Max-Age", "3600");
                    return response
                }

                Method.POST -> {
                    session.parseBody(HashMap<String, String>())

                    returnData = runBlocking {
                        when (uri) {
                            "/api/getMainTabs" -> VideoSourceController.getMainTabs(session.parameters)
                            "/api/getSubTabs" -> VideoSourceController.getSubTabs(session.parameters)
                            "/api/getPageContent" -> VideoSourceController.getContent(session.parameters)
                            "/api/getDetailed" -> VideoSourceController.getDetailed(session.parameters)
                            "/api/getPlayInfo" -> VideoSourceController.getPlayInfo(session.parameters)
                            "/api/search" -> VideoSourceController.search(session.parameters)
                            "/api/downCode" -> SourceController.downCode(session.parameters)
                            else -> null
                        }
                    }
                }

                Method.GET -> {
                    val parameters = session.parameters

                    returnData = when (uri) {
                        "/api/getSources" -> VideoSourceController.sources
                        "/stream/p/p/p/p/p/p/p/p/p/p/p/p" -> {
                            val rData = ReturnData()
                            val url = parameters["url"]?.firstOrNull()
                            if(url != null){
                                val connection = URL(url).openConnection() as HttpURLConnection

                                // Handle Range Requests
                                val rangeHeader = session.headers["range"]
                                rangeHeader?.let {
                                    val rangeValue = it.replace("bytes=", "")
                                    connection.setRequestProperty("Range", "bytes=$rangeValue")
                                }

                                rData.setData(connection)
                            }
                            rData
                        }
                        else -> null
                    }
                }

                else -> Unit
            }

            if (returnData == null) {
                if (uri.endsWith("/"))
                    uri += "index.html"
                return assetsWeb.getResponse(uri)
            }

            val response = if (returnData.data is HttpURLConnection) {
                val connection = returnData.data as HttpURLConnection
                connection.connect()

                // Check response status
                val responseCode = connection.responseCode
                if (responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                    return newFixedLengthResponse(
                        Response.Status.INTERNAL_ERROR,
                        "text/plain",
                        "Error fetching remote resources"
                    )
                }

                // Prepare the response
                val contentType = connection.contentType
                val contentRange = connection.getHeaderField("Content-Range")
                val contentLength = connection.contentLengthLong

                val response = newChunkedResponse(
                    if (responseCode == HttpURLConnection.HTTP_PARTIAL)
                        Response.Status.PARTIAL_CONTENT
                    else
                        Response.Status.OK,
                    contentType,
                    BufferedInputStream(connection.inputStream)
                )

                // Add headers
                contentRange?.let { response.addHeader("Content-Range", it) }
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", contentLength.toString())

                response
            } else if (returnData.data is Bitmap) {
                val outputStream = ByteArrayOutputStream()
                (returnData.data as Bitmap).compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                val byteArray = outputStream.toByteArray()
                outputStream.close()
                val inputStream = ByteArrayInputStream(byteArray)
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    inputStream,
                    byteArray.size.toLong()
                )
            } else {
                val data = returnData.data
                if (data is List<*> && data.size > 3000) {
                    val pipe = Pipe(16 * 1024)
                    Coroutine.async {
                        pipe.sink.buffer().outputStream().bufferedWriter(Charsets.UTF_8).use {
                            it.toJson()
                        }
                    }
                    newChunkedResponse(
                        Response.Status.OK,
                        "application/json",
                        pipe.source.buffer().inputStream()
                    )
                } else {
                    newFixedLengthResponse(returnData.toJson())
                }
            }
            response.addHeader("Access-Control-Allow-Methods", "GET, POST")
//            response.addHeader("Access-Control-Allow-Origin", session.headers["origin"])
            response.addHeader("Access-Control-Allow-Origin", "*")
            LogUtils.d(TAG, "${session.method.name} - $uri - ${session.queryParameterString} - End($startAt)")
            return response
        } catch (e: Exception) {
            LogUtils.d(TAG, "${session.method.name} - $uri - ${session.queryParameterString} - Error End($startAt)\n$e\n${e.stackTraceStr}")
            return newFixedLengthResponse(e.message)
        }

    }

    companion object {
        private const val TAG = "HttpServer"
    }

}
