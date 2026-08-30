package com.heyanle.easybangumi4.ui.ai

import android.util.Base64
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

object CodexAuthManager {
    sealed class LoginState {
        data object Idle : LoginState()
        data object Requesting : LoginState()
        data class Waiting(val verificationUrl: String, val userCode: String) : LoginState()
        data class Success(val accountId: String) : LoginState()
        data class Error(val message: String) : LoginState()
    }

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state = _state.asStateFlow()

    suspend fun login(proxyConfig: AiProxyConfig? = null) = withContext(Dispatchers.IO) {
        try {
            _state.value = LoginState.Requesting
            val client = client(proxyConfig)
            val device = requestDeviceCode(client)
            _state.value = LoginState.Waiting(VERIFY_URL, device.userCode)
            val code = pollAuthorization(client, device)
            val tokens = exchangeCode(client, code)
            val accountId = accountId(tokens.idToken)
                ?: throw IOException("登录令牌缺少 ChatGPT account id")
            AiWorkspaceStore.saveCodexAuth(
                CodexAuth(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken,
                    idToken = tokens.idToken,
                    accountId = accountId,
                )
            )
            _state.value = LoginState.Success(accountId)
        } catch (cancelled: CancellationException) {
            _state.value = LoginState.Idle
            throw cancelled
        } catch (error: Throwable) {
            _state.value = LoginState.Error(error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun refresh(auth: CodexAuth, proxyConfig: AiProxyConfig? = null): CodexAuth = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", auth.refreshToken)
            .build()
        val response = execute(client(proxyConfig), Request.Builder().url(TOKEN_URL).post(body).build())
        val json = JsonParser.parseString(response).asJsonObject
        val updated = auth.copy(
            accessToken = json.get("access_token")?.asString ?: auth.accessToken,
            refreshToken = json.get("refresh_token")?.asString ?: auth.refreshToken,
            idToken = json.get("id_token")?.asString ?: auth.idToken,
            updatedAt = System.currentTimeMillis(),
        )
        AiWorkspaceStore.saveCodexAuth(updated)
        updated
    }

    fun logout() {
        AiWorkspaceStore.saveCodexAuth(null)
        _state.value = LoginState.Idle
    }

    fun resetState() {
        if (_state.value !is LoginState.Waiting && _state.value !is LoginState.Requesting) {
            _state.value = LoginState.Idle
        }
    }

    private fun requestDeviceCode(client: OkHttpClient): DeviceCode {
        val payload = "{\"client_id\":\"$CLIENT_ID\"}"
        val request = Request.Builder()
            .url(USER_CODE_URL)
            .post(payload.toRequestBody(JSON))
            .build()
        val json = JsonParser.parseString(execute(client, request)).asJsonObject
        return DeviceCode(
            id = json.get("device_auth_id")?.asString ?: error("登录响应缺少 device_auth_id"),
            userCode = json.get("user_code")?.asString ?: json.get("usercode")?.asString
                ?: error("登录响应缺少 user_code"),
            intervalSeconds = json.get("interval")?.asString?.toLongOrNull()?.coerceIn(1, 30) ?: 5,
        )
    }

    private suspend fun pollAuthorization(client: OkHttpClient, device: DeviceCode): AuthorizationCode {
        val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
        while (System.currentTimeMillis() < deadline) {
            val payload = "{\"device_auth_id\":\"${device.id}\",\"user_code\":\"${device.userCode}\"}"
            val request = Request.Builder().url(POLL_URL).post(payload.toRequestBody(JSON)).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                    return AuthorizationCode(
                        code = json.get("authorization_code")?.asString ?: error("授权响应缺少 authorization_code"),
                        verifier = json.get("code_verifier")?.asString ?: error("授权响应缺少 code_verifier"),
                    )
                }
                if (response.code != 403 && response.code != 404) {
                    throw IOException("设备授权轮询失败: HTTP ${response.code}")
                }
            }
            delay(device.intervalSeconds * 1000)
        }
        throw IOException("ChatGPT 登录已超时，请重新开始")
    }

    private fun exchangeCode(client: OkHttpClient, code: AuthorizationCode): Tokens {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code.code)
            .add("redirect_uri", "$ISSUER/deviceauth/callback")
            .add("client_id", CLIENT_ID)
            .add("code_verifier", code.verifier)
            .build()
        val json = JsonParser.parseString(
            execute(client, Request.Builder().url(TOKEN_URL).post(body).build())
        ).asJsonObject
        return Tokens(
            idToken = json.get("id_token")?.asString ?: error("令牌响应缺少 id_token"),
            accessToken = json.get("access_token")?.asString ?: error("令牌响应缺少 access_token"),
            refreshToken = json.get("refresh_token")?.asString ?: error("令牌响应缺少 refresh_token"),
        )
    }

    private fun execute(client: OkHttpClient, request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("登录请求失败: HTTP ${response.code} ${body.take(500)}")
            return body
        }
    }

    private fun client(proxyConfig: AiProxyConfig?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        if (proxyConfig != null && proxyConfig.host.isNotBlank() && proxyConfig.port in 1..65535) {
            val type = if (proxyConfig.type.equals(PROXY_SOCKS5, true)) Proxy.Type.SOCKS else Proxy.Type.HTTP
            builder.proxy(Proxy(type, InetSocketAddress(proxyConfig.host, proxyConfig.port)))
            if (proxyConfig.username.isNotBlank()) {
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", okhttp3.Credentials.basic(proxyConfig.username, proxyConfig.password))
                        .build()
                }
            }
        }
        return builder.build()
    }

    private fun accountId(jwt: String): String? = runCatching {
        val payload = jwt.split('.')[1]
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        JsonParser.parseString(decoded).asJsonObject
            .getAsJsonObject("https://api.openai.com/auth")
            ?.get("chatgpt_account_id")?.asString
    }.getOrNull()

    private data class DeviceCode(val id: String, val userCode: String, val intervalSeconds: Long)
    private data class AuthorizationCode(val code: String, val verifier: String)
    private data class Tokens(val idToken: String, val accessToken: String, val refreshToken: String)

    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val ISSUER = "https://auth.openai.com"
    private const val USER_CODE_URL = "$ISSUER/api/accounts/deviceauth/usercode"
    private const val POLL_URL = "$ISSUER/api/accounts/deviceauth/token"
    private const val TOKEN_URL = "$ISSUER/oauth/token"
    const val VERIFY_URL = "$ISSUER/codex/device"
    private val JSON = "application/json; charset=utf-8".toMediaType()
}
