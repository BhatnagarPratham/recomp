package app.recomp.domain.ai

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * The base URL of an OpenAI-compatible server (AI-05, TRD §6.2), checked before anything is sent to it.
 * `https` may go anywhere. Plain `http` may only go to this phone, a private-range address or a `.local`
 * name, and a request over `http` never carries the API key ([sendsKey]).
 */
data class AiUrl(
    /** The URL as requests use it: no trailing slash, e.g. `https://openrouter.ai/api/v1`. */
    val base: String,
    val host: String,
    val https: Boolean,
    /** This phone itself (`localhost`, 127.0.0.0/8, ::1). */
    val loopback: Boolean,
    /** On the local network: a private-range or link-local address, or a `.local` name. Android asks for a permission first. */
    val localNetwork: Boolean,
) {
    /** Whether a request to this URL may carry the key: never over plain http. */
    val sendsKey: Boolean get() = https

    /** A local server needs no internet connection, so the gate's offline check doesn't apply. */
    val local: Boolean get() = loopback || localNetwork

    enum class Problem {
        EMPTY,
        NOT_A_URL,

        /** Plain http to somewhere that isn't this phone or the local network. */
        HTTP_NOT_LOCAL,

        /** A user name or password inside the URL, which would be stored and exported with the settings. */
        CREDENTIALS,

        /** A query or fragment: the base URL is a path that `/chat/completions` is added to. */
        QUERY,
    }

    sealed interface Check {
        data class Ok(val url: AiUrl) : Check
        data class Bad(val problem: Problem) : Check
    }

    companion object {
        private val IPV4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

        fun check(text: String?): Check {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return Check.Bad(Problem.EMPTY)
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return Check.Bad(Problem.NOT_A_URL)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") return Check.Bad(Problem.NOT_A_URL)
            // A host the parser couldn't read (underscores, odd characters) leaves `host` null: refuse it rather than guess.
            val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return Check.Bad(Problem.NOT_A_URL)
            if (uri.rawUserInfo != null) return Check.Bad(Problem.CREDENTIALS)
            if (uri.rawQuery != null || uri.rawFragment != null) return Check.Bad(Problem.QUERY)

            val kind = kind(host)
            val https = scheme == "https"
            if (!https && kind == Kind.PUBLIC) return Check.Bad(Problem.HTTP_NOT_LOCAL)
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return Check.Ok(AiUrl("$scheme://${uri.host.lowercase()}$port$path", host, https, kind == Kind.LOOPBACK, kind == Kind.LOCAL_NETWORK))
        }

        fun ok(text: String?): AiUrl? = (check(text) as? Check.Ok)?.url

        private enum class Kind { LOOPBACK, LOCAL_NETWORK, PUBLIC }

        /**
         * Decided from the text alone, with no DNS lookup: an address literal by its range, a name only when it is
         * `localhost` or ends in `.local`. Anything else, including a shorthand address such as `127.1`, is public.
         */
        private fun kind(host: String): Kind {
            IPV4.matchEntire(host)?.let { m ->
                val o = m.groupValues.drop(1).map { it.toInt() }
                if (o.any { it > 255 }) return Kind.PUBLIC
                return when {
                    o[0] == 127 -> Kind.LOOPBACK
                    o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) -> Kind.LOCAL_NETWORK
                    // Link-local, and the shared range 100.64.0.0/10 (carrier NAT, Tailscale), which Android counts as local network too.
                    (o[0] == 169 && o[1] == 254) || (o[0] == 100 && o[1] in 64..127) -> Kind.LOCAL_NETWORK
                    else -> Kind.PUBLIC
                }
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                // A literal, so this parses and never looks anything up.
                val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return Kind.PUBLIC
                val unique = address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
                return when {
                    address.isLoopbackAddress -> Kind.LOOPBACK
                    address.isSiteLocalAddress || address.isLinkLocalAddress || unique -> Kind.LOCAL_NETWORK
                    else -> Kind.PUBLIC
                }
            }
            return when {
                host == "localhost" || host.endsWith(".localhost") -> Kind.LOOPBACK
                host.endsWith(".local") -> Kind.LOCAL_NETWORK
                else -> Kind.PUBLIC
            }
        }
    }
}
