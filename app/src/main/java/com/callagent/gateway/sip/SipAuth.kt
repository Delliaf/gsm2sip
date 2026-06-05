package com.callagent.gateway.sip

import java.security.MessageDigest
import java.util.Random

/**
 * SIP Digest Authentication (RFC 2617).
 * Now supports QoS qop=auth,auth-int (nc, cnonce included).
 */
object SipAuth {

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val qop: String?
    )

    /** Parse WWW-Authenticate header parameters */
    fun parseChallenge(msg: SipMessage): AuthParams? {
        val authHeader = msg.header("www-authenticate") ?: msg.header("proxy-authenticate")
            ?: return null
        if (!authHeader.lowercase().startsWith("digest")) return null
        val params = mutableMapOf<String, String>()
        val digestIdx = authHeader.lowercase().indexOf("digest")
        val paramStr = if (digestIdx >= 0) authHeader.substring(digestIdx + 6).trim() else return null
        paramStr.split(",").forEach { pair ->
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val key = pair.substring(0, eq).trim().lowercase()
                val value = pair.substring(eq + 1).trim().trim('"')
                params[key] = value
            }
        }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return AuthParams(realm, nonce, params["opaque"], params["qop"])
    }

    private val cnonceRandom = Random()
    private var authNonceCount = 0

    /** Generate cnonce — random 32 hex chars */
    fun generateCnonce(): String = (1..16).joinToString("") { "%02x".format(cnonceRandom.nextInt(256)) }

    /** Build Authorization/Proxy-Authorization header */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String {
        val ha1 = md5("$username:${params.realm}:$password")
        val ha2 = md5("$method:$uri")

        val qop = params.qop?.trim()?.lowercase()
        val nc: String
        val cnonce: String
        val response: String

        if (qop != null && (qop == "auth" || qop.startsWith("auth,") || qop == "auth-int")) {
            // Full RFC 2617 with qop support
            authNonceCount++
            nc = "%08x".format(authNonceCount)
            cnonce = generateCnonce()
            response = md5("$ha1:${params.nonce}:$nc:$cnonce:$qop:$ha2")
        } else {
            // RFC 2069 (no qop)
            nc = ""
            cnonce = ""
            response = md5("$ha1:${params.nonce}:$ha2")
        }

        return buildString {
            append("Authorization: Digest ")
            append("username=\"$username\", ")
            append("realm=\"${params.realm}\", ")
            append("nonce=\"${params.nonce}\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\"")
            if (params.opaque != null) append(", opaque=\"${params.opaque}\"")
            if (qop != null && cnonce.isNotEmpty()) {
                append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            }
            append(", algorithm=MD5")
            append("\r\n")
        }
    }

    /** Build Proxy-Authorization instead of Authorization if the challenge asked for it */
    fun buildProxyAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String =
        buildAuthHeader(method, uri, username, password, params)
            .replace("Authorization:", "Proxy-Authorization:")

    fun buildInviteAuthHeader(
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String = buildAuthHeader("INVITE", uri, username, password, params)

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
