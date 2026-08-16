package com.authorss81.noteflow.services.localsend

/**
 * B1-NET-06 discovery gate for the LocalSend sender (pure JVM, unit-testable).
 *
 * The finding (`docs/security-report.md` B1-NET-06, LOW): opening the "Send to
 * Nearby Device" dialog used to make the device (a) run a `/24` HTTP register
 * sweep — POSTing a LocalSend identity to every IPv4 of the active subnet on
 * port 53317 (`legacyHttpScan`), and (b) advertise the EXACT device model
 * (`Build.MODEL`) plus presence to every LAN host via broadcast/multicast
 * announces — with zero user confirmation. Any LAN host (or passive AP
 * monitoring) could fingerprint the app's presence, precise handset and local
 * IP without the user ever consenting.
 *
 * This policy is the single decision table for what LocalSend may transmit on
 * the local network, and encodes the post-fix rules:
 *  - LocalSend is passive until the user EXPLICITLY asks to search: opening
 *    the dialog transmits nothing, and a search happens only via the user
 *    tapping the explicit action. No `LaunchedEffect`, no auto-probe, no
 *    background announce.
 *  - Discovery defaults to UDP announce/listen only. The `/24` HTTP register
 *    sweep — the most fingerprint-y probe — is DISABLED by default and runs
 *    only when the user explicitly opts in for it (per-search, not persisted),
 *    so a plain "find nearby devices" tap never blasts 254 HTTP POSTs at the
 *    subnet.
 *  - The sender announces a FIXED, user-neutral identity and NEVER a device
 *    model: `Build.MODEL` (and any OS/app/version marker) is excluded from the
 *    announce/extractedText carrying fields.
 *
 * API 26+ floor: the gating is caller-agnostic UI logic, no platform dependency,
 * no fallback needed (the dialog's per-search checkbox is available on every
 * supported API level).
 */
object LocalSendDiscoveryPolicy {

    /** A search only ever runs on an explicit user action — never on dialog open. */
    const val DISCOVERY_REQUIRES_EXPLICIT_USER_ACTION = true

    /** The `/24` HTTP register sweep is OFF by default (opt-in per search). */
    const val LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT = false

    /** Fixed, neutral sender identity — never `Build.MODEL`. */
    const val SENDER_ALIAS = "InkFlow"

    /** The sender NEVER advertises a device model. */
    val senderDeviceModel: String? = null

    /**
     * Whether discovery may currently transmit. Currently ALWAYS requires an
     * explicit user action — there is no code path that starts a search without
     * one, and this exists so a future caller cannot regress to auto-discovery.
     */
    fun mayRunDiscovery(userInitiated: Boolean): Boolean = userInitiated

    /**
     * The sweep gate: the `/24` HTTP register sweep may run ONLY when the user
     * explicitly opted in for it (per-search checkbox). Fails closed by default.
     */
    fun mayRunLegacyHttpScan(userOptedIn: Boolean): Boolean = userOptedIn
}