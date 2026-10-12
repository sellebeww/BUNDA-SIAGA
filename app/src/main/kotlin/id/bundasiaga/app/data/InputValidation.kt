package id.bundasiaga.app.data

import java.net.URI

object InputValidation {
    fun phone(value: String): Boolean {
        val raw = value.trim()
        if (!raw.matches(Regex("\\+?[0-9() .-]+"))) return false
        val digits = raw.filter { it in '0'..'9' }
        return digits.length in 5..15 && digits.any { it != '0' }
    }

    fun serverUrl(value: String, allowHttp: Boolean): Boolean {
        if (value.isBlank()) return true
        return runCatching {
            val uri = URI(value.trim())
            (uri.scheme == "https" || (allowHttp && uri.scheme == "http")) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
    }
}
