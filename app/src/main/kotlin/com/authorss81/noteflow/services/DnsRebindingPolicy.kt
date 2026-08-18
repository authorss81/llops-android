package com.authorss81.noteflow.services

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory

/**
 * R2-B1N-02 (phase-144): resolve-and-pin for the user-driven and plugin-driven
 * fetchers — the DNS-rebinding hardening layer ON TOP of the textual
 * [SsrfHostPolicy] blocklist ([SsrfHostPolicy]'s KDoc explicitly leaves
 * resolution to the transport; this object is that transport seam).
 *
 * The textual blocklist only sees the STRING the user/plugin pasted. A
 * DNS-rebinding domain answers the first query with a public IP and the
 * connect-time query with `127.0.0.1` / `169.254.169.254` / a LAN address, so a
 * plain `URL.openConnection()` — which re-resolves the host at connect time
 * inside the platform DNS cache — could still reach an internal host the
 * blocklist never saw.
 *
 * This object closes it the way B1-NET-04's residual finding describes, per
 * hop, in TWO layers that match the two DNS queries:
 *
 *  1. **[resolveAndPin]** resolves the name ONCE (the `InetAddress.getAllByName`
 *     the app controls) and validates EVERY returned A/AAAA against the same
 *     structural ranges [SsrfHostPolicy.blockedReason] uses. If ANY address is
 *     internal/reserved the hop is REFUSED outright (fail-closed) — a rebinding
 *     domain that ever answers us with an internal address is dead on arrival.
 *  2. **[PinnedSslSocketFactory]** + **[applied][applyPinToConnection] pin the
 *     actual CONNECT to exactly the checked addresses: the layered TLS create
 *     discards any plaintext socket the platform already connected (its
 *     connect-time DNS could have rebound to an internal host) and reconnects
 *     to one of the validated public addresses, so neither the TCP connect nor
 *     the TLS handshake can ever reach an internal endpoint.
 *
 * Pure JVM (`java.net`/`javax.net.ssl` only). Every behavior is unit-tested
 * with an injectable resolver seam — no external network in tests.
 */
object DnsRebindingPolicy {

    /** The platform DNS. [resolveAndPin] catches its failures into a refusal. */
    val DEFAULT_RESOLVER: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }

    /**
     * Outcome of resolving + validating one hop's host.
     *
     * @property addresses the DISTINCT validated (public) addresses a connect
     *   may target. A host that resolves to public AND internal addresses is
     *   [Refused] whole (fail closed — never a partial pin).
     */
    sealed class Verdict {
        data class Pinned(val host: String, val addresses: List<InetAddress>) : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    /**
     * Resolve [host] and validate EVERY returned A/AAAA against the
     * [SsrfHostPolicy] ranges (loopback, RFC-1918, link-local/cloud-metadata,
     * CGNAT, ULA, mDNS). Returns a [Verdict.Pinned] with the distinct public
     * addresses ONLY when the whole resolution is safe; returns a
     * [Verdict.Refused] — never throws — for a theory of internal answer, an
     * unresolved name, a resolver failure or an empty answer.
     *
     * @param resolver injectable (`DEFAULT_RESOLVER` in production) so unit
     *   tests prove the rebinding rejection without a network.
     */
    fun resolveAndPin(
        host: String,
        resolver: (String) -> Array<InetAddress> = DEFAULT_RESOLVER
    ): Verdict {
        if (host.isBlank()) {
            return Verdict.Refused("Refused: that URL does not include a host.")
        }
        val addresses = try {
            resolver(host)
        } catch (e: UnknownHostException) {
            return Verdict.Refused("Unable to reach '$host' — the name did not resolve.")
        } catch (e: SecurityException) {
            return Verdict.Refused("Unable to reach '$host' — DNS access was denied.")
        } catch (e: Exception) {
            return Verdict.Refused("Unable to reach '$host' — name resolution failed.")
        }
        if (addresses.isEmpty()) {
            return Verdict.Refused("Unable to reach '$host' — it resolved to no addresses.")
        }
        val distinct = addresses
            .distinctBy { it.hostAddress }
            .toList()
        if (distinct.isEmpty()) {
            return Verdict.Refused("Unable to reach '$host' — it resolved to no addresses.")
        }
        for (addr in distinct) {
            val blocked = SsrfHostPolicy.blockedReason(addr.hostAddress ?: "")
            if (blocked != null) {
                return Verdict.Refused(
                    "Refused to fetch '$host': DNS resolved it to the internal address " +
                        "${addr.hostAddress} ($blocked). This looks like a DNS-rebinding answer — " +
                        "the fetch is not allowed to reach internal hosts."
                )
            }
        }
        return Verdict.Pinned(host, distinct)
    }

    /**
     * Attach the pinning TLS machinery to [conn] for [host] with the already-
     * validated [addresses]. Best-effort and safe under every platform:
     *
     * - The URL host is UNCHANGED (a hostname, never an IP literal), so the
     *   platform's own certificate hostname check keeps applying to it; the
     *   custom [PinnedSslSocketFactory] only controls WHICH address the
     *   underlying sockets connect to.
     * - The custom [HostnameVerifier] is installed only when the platform hands
     *   us a non-null default hostname verifier to delegate to — otherwise it
     *   is left unset and the platform's built-in hostname verification (which
     *   is what applies when no verifier is configured) stays in force.
     * - On a plain-HTTP [HttpURLConnection] (or a test fake that is not an
     *   [HttpsURLConnection]) nothing is set; the caller's per-hop scheme /
     *   SSRF gates still hold.
     */
    fun applyPinToConnection(
        conn: HttpURLConnection,
        host: String,
        addresses: List<InetAddress>,
        connectTimeoutMs: Int
    ) {
        val https = conn as? HttpsURLConnection ?: return
        https.sslSocketFactory = PinnedSslSocketFactory(addresses, connectTimeoutMs)
        val defaultVerifier = runCatching { HttpsURLConnection.getDefaultHostnameVerifier() }.getOrNull()
        if (defaultVerifier != null) {
            https.hostnameVerifier = PinnedHostnameVerifier(host, defaultVerifier)
        }
    }

    /**
     * The connect pin. Every entry point an HTTP(S) client can call is routed
     * to a pinned address instead of the platform re-resolving the host:
     *
     * - the direct [createSocket] overloads connect straight to a validated
     *   address (never DNS);
     * - the LAYERED `createSocket(Socket, host, port, autoClose)` — what
     *   `HttpsURLConnection` invokes after it pre-connected its own plaintext
     *   socket — first checks that pre-connected peer against the pin: a socket
     *   that reached a non-pinned (rebound) address is CLOSED and the connect
     *   is rebuilt to a pinned address, so a DNS rebinding between our
     *   [resolveAndPin] call and the platform connect can never carry a
     *   handshake to an internal host (the most a rebinding server can see is
     *   an aborted, TLS-less TCP connect).
     */
    class PinnedSslSocketFactory(
        private val pinnedAddresses: List<InetAddress>,
        private val connectTimeoutMs: Int,
        private val delegate: SSLSocketFactory =
            SSLSocketFactory.getDefault() as SSLSocketFactory
    ) : SSLSocketFactory() {

        init {
            require(pinnedAddresses.isNotEmpty()) {
                "a connect pin needs at least one validated address"
            }
        }

        private val nextSlot = AtomicInteger(0)

        private fun selectAddress(): InetAddress {
            val i = nextSlot.getAndIncrement()
            return pinnedAddresses[i.rem(pinnedAddresses.size)]
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        override fun createSocket(host: String, port: Int): Socket = connectPinned(host, port)

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
            val fresh = Socket()
            try {
                fresh.bind(InetSocketAddress(localHost, localPort))
                fresh.connect(InetSocketAddress(selectAddress(), port), connectTimeoutMs)
                return delegate.createSocket(fresh, host, port, true)
            } catch (e: Exception) {
                runCatching { fresh.close() }
                throw e
            }
        }

        override fun createSocket(host: InetAddress, port: Int): Socket = connectPinned(host.hostAddress, port)

        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket(host.hostAddress, port, localHost, localPort)

        /**
         * The layered entry the platform actually uses for HTTPS. [socket] is
         * the already-connected plaintext socket the platform rooted at
         * connect-time DNS; enforce the pin on it.
         */
        override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            val connectedTo = runCatching { socket.inetAddress }.getOrNull()
            if (connectedTo != null && pinnedAddresses.any { it == connectedTo }) {
                return delegate.createSocket(socket, host, port, autoClose)
            }
            // The platform pre-connected somewhere outside the validated set
            // (or didn't connect): discard it and pin a fresh connect.
            runCatching { socket.close() }
            return connectPinned(host, port)
        }

        private fun connectPinned(host: String, port: Int): Socket {
            val fresh = Socket()
            try {
                fresh.connect(InetSocketAddress(selectAddress(), port), connectTimeoutMs)
                return delegate.createSocket(fresh, host, port, true)
            } catch (e: Exception) {
                runCatching { fresh.close() }
                throw e
            }
        }
    }

    /**
     * Hostname-verification belt-and-braces: the session's [hostname] must be
     * the host the hop validated ([expectedHost]), that host must never be a
     * blocked destination, and only then is the check delegated to the
     * platform verifier (which does the real certificate SAN match against
     * [expectedHost]). Installed via [applyPinToConnection] only when a
     * platform default verifier exists to delegate to.
     */
    class PinnedHostnameVerifier(
        private val expectedHost: String,
        private val delegate: HostnameVerifier
    ) : HostnameVerifier {
        override fun verify(hostname: String, session: SSLSession): Boolean {
            if (hostname != expectedHost) return false
            if (SsrfHostPolicy.blockedReason(expectedHost) != null) return false
            return delegate.verify(hostname, session)
        }
    }
}