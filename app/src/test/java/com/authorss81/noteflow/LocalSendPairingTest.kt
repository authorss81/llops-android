package com.authorss81.noteflow

import com.authorss81.noteflow.services.localsend.InMemoryLocalSendPairedDeviceStore
import com.authorss81.noteflow.services.localsend.LocalSendDevice
import com.authorss81.noteflow.services.localsend.LocalSendGate
import com.authorss81.noteflow.services.localsend.LocalSendPairedDeviceCodec
import com.authorss81.noteflow.services.localsend.LocalSendPairing
import com.authorss81.noteflow.services.localsend.LocalSendPairingCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the B1-NET-02 (phase-41) confirmed-pairing fix:
 * the pairing/PIN handshake, TOFU fingerprint persistence, HTTPS-only
 * enforcement and the rejection of unknown / unpaired / non-TLS receivers.
 * No network, no Android framework, no receivers.
 */
class LocalSendPairingTest {

    // ---- helpers ----

    private fun device(
        address: String = "192.168.1.42",
        protocol: String = "https",
        fingerprint: String? = "ABC:DEF01234:5678",
        alias: String = "Galaxy S24"
    ) = LocalSendDevice(
        address = address,
        port = 53317,
        protocol = protocol,
        alias = alias,
        version = "2.0",
        deviceModel = null,
        deviceType = "phone",
        fingerprint = fingerprint,
        download = false
    )

    private val store = InMemoryLocalSendPairedDeviceStore()

    // ---- pairing code (the out-of-band human-readable code) ----

    @Test
    fun pairingCode_isSixDigitsAndStable() {
        val fingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        val first = LocalSendPairingCodes.pairingCode(fingerprint)
        val second = LocalSendPairingCodes.pairingCode("aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899")
        assertEquals(6, first.length)
        assertTrue(first.all { it.isDigit() })
        assertEquals("Same fingerprint must derive the same code (colons/case-normalized).", first, second)
    }

    @Test
    fun pairingCode_differsForDifferentFingerprints() {
        val a = LocalSendPairingCodes.pairingCode("AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99")
        val b = LocalSendPairingCodes.pairingCode("11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11")
        assertFalse(a == b)
    }

    @Test
    fun pairingCode_blankFingerprintIsEmpty() {
        assertEquals("", LocalSendPairingCodes.pairingCode(""))
        assertEquals("", LocalSendPairingCodes.pairingCode(":::"))
    }

    @Test
    fun formattedFingerprint_groupsByFourInUpper() {
        assertEquals(
            "ABCD:EF01:2345",
            LocalSendPairingCodes.formattedFingerprint("ab:cd:ef:01:23:45")
        )
        assertEquals("", LocalSendPairingCodes.formattedFingerprint(""))
    }

    @Test
    fun normalizeFingerprint_stripsSeparatorsAndLowercases() {
        assertEquals("abcdef012345", LocalSendPairingCodes.normalizeFingerprint("AB:CD:EF:01:23:45"))
    }

    // ---- gate: HTTPS-only + known fingerprint + paired (TOFU) ----

    @Test
    fun gate_deniesHttpOnlyReceiver() {
        val httpDevice = device(protocol = "http", fingerprint = "ABCDEF")
        val result = LocalSendPairing.gate(httpDevice, store)
        assertTrue(result is LocalSendGate.Denied)
        assertTrue((result as LocalSendGate.Denied).reason.contains("secure"))
    }

    @Test
    fun gate_deniesReceiverWithoutFingerprint() {
        val noFp = device(fingerprint = null)
        val result = LocalSendPairing.gate(noFp, store)
        assertTrue(result is LocalSendGate.Denied)
        assertTrue((result as LocalSendGate.Denied).reason.contains("fingerprint"))
    }

    @Test
    fun gate_deniesUnpairedHttpsReceiver() {
        val result = LocalSendPairing.gate(device(), store)
        assertTrue("An unknown/unpaired receiver must be refused.", result is LocalSendGate.Denied)
        assertTrue((result as LocalSendGate.Denied).reason.contains("not paired"))
    }

    @Test
    fun gate_allowsPairedHttpsReceiver() {
        val fp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        val paired = LocalSendPairing.confirmPairing(
            checkNotNull(LocalSendPairing.startPairing(device(fingerprint = fp))),
            LocalSendPairingCodes.pairingCode(fp)
        )
        assertNotNull(paired)
        store.put(paired!!)

        val gate = LocalSendPairing.gate(device(fingerprint = fp), store)
        assertTrue(gate is LocalSendGate.Allowed)
        assertEquals(paired.normalizedFingerprint, (gate as LocalSendGate.Allowed).paired.normalizedFingerprint)
    }

    @Test
    fun gate_deniesFingerprintChangeAfterPairing() {
        val originalFp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        val original = LocalSendPairing.confirmPairing(
            checkNotNull(LocalSendPairing.startPairing(device(fingerprint = originalFp))),
            LocalSendPairingCodes.pairingCode(originalFp)
        )
        store.put(original!!)

        // Attacker re-announces the SAME alias with THEIR OWN fingerprint.
        val forged = device(fingerprint = "11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF:11")
        val gate = LocalSendPairing.gate(forged, store)
        assertTrue("A fingerprint change after pairing must be refused.", gate is LocalSendGate.Denied)
    }

    @Test
    fun gate_allowsPairedDeviceAcrossCaseSeparatorVariants() {
        val fp = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899"
        val paired = LocalSendPairing.confirmPairing(
            checkNotNull(LocalSendPairing.startPairing(device(fingerprint = fp))),
            LocalSendPairingCodes.pairingCode(fp)
        )
        store.put(paired!!)
        val gate = LocalSendPairing.gate(device(fingerprint = fp.lowercase()), store)
        assertTrue(gate is LocalSendGate.Allowed)
    }

    // ---- pairing handshake ----

    @Test
    fun startPairing_rejectsHttpReceiver() {
        assertNull(LocalSendPairing.startPairing(device(protocol = "http", fingerprint = "ABCD")))
        assertNull(LocalSendPairing.startPairing(device(fingerprint = null)))
    }

    @Test
    fun startPairing_normalizesFingerprintAndDerivesCode() {
        val request = LocalSendPairing.startPairing(device(fingerprint = "AB:CD:EF:01"))
        assertNotNull(request)
        assertEquals("abcdef01", request!!.fingerprint)
        assertEquals(LocalSendPairingCodes.pairingCode("AB:CD:EF:01"), request.code)
    }

    @Test
    fun confirmPairing_requiresMatchingCode() {
        val fp = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
        val request = checkNotNull(LocalSendPairing.startPairing(device(fingerprint = fp)))
        val wrongCode = if (request.code == "000001") "000002" else "000001"
        assertNull("A mismatched pairing code must never pair.", LocalSendPairing.confirmPairing(request, wrongCode))
        assertFalse("A mismatched code must not persist a pairing.", LocalSendPairing.pair(store, request, wrongCode))
        assertNull(store.find(fp))
    }

    @Test
    fun confirmPairing_codeMatchReturnsNormalizedDevice() {
        val fp = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
        val request = checkNotNull(LocalSendPairing.startPairing(device(fingerprint = fp, alias = "Desk")))
        val paired = LocalSendPairing.confirmPairing(request, request.code)
        assertNotNull(paired)
        assertEquals(request.fingerprint, paired!!.normalizedFingerprint)
        assertEquals("Desk", paired.alias)
        assertTrue(paired.pairedAtMillis > 0)
    }

    @Test
    fun pair_persistsToTofuStore() {
        val fp = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"
        val request = checkNotNull(LocalSendPairing.startPairing(device(fingerprint = fp)))
        assertTrue(LocalSendPairing.pair(store, request, request.code))
        val found = store.find(fp)
        assertNotNull("TOFU anchor must be persisted after an explicit pair.", found)
        assertEquals(1, store.all().size)
        assertEquals(request.fingerprint, store.all()[0].normalizedFingerprint)
    }

    // ---- TOFU store (in-memory) ----

    @Test
    fun inMemoryStore_findPutRemoveAll() {
        val fp = "abcdef123456"
        val device1 = LocalSendPairing.confirmPairing(
            LocalSendPairingRequestTest.requireRequest(fp),
            LocalSendPairingCodes.pairingCode(fp)
        )
        store.put(device1!!)
        assertNotNull(store.find(fp))
        assertTrue(store.find(fp.uppercase()) != null)
        assertTrue(store.remove(fp))
        assertNull(store.find(fp))
        assertFalse(store.remove(fp))
    }

    // ---- persisted blob serde ----

    @Test
    fun codec_roundTripPreservesNormalizedIdentity() {
        val device = LocalSendPairedDeviceCodec.decode(
            LocalSendPairedDeviceCodec.encode(
                com.authorss81.noteflow.services.localsend.LocalSendPairedDevice(
                    fingerprint = "AB:CD:EF",
                    alias = "Desk",
                    pairedAtMillis = 1234L
                )
            )
        )
        assertNotNull(device)
        assertEquals("abcdef", device!!.normalizedFingerprint)
        assertEquals("Desk", device.alias)
        assertEquals(1234L, device.pairedAtMillis)
    }

    @Test
    fun codec_garbageDoesNotCrash() {
        assertNull(LocalSendPairedDeviceCodec.decode("not json"))
        assertNull(LocalSendPairedDeviceCodec.decode(""))
        assertNull(LocalSendPairedDeviceCodec.decode("""{"fingerprint":null}"""))
        assertNull(LocalSendPairedDeviceCodec.decode("""{"t":1}"""))
    }
}

/** Tiny helper so the store test can build a request without repeating the code. */
private object LocalSendPairingRequestTest {
    fun requireRequest(fingerprint: String) =
        checkNotNull(LocalSendPairing.startPairing(
            com.authorss81.noteflow.services.localsend.LocalSendDevice(
                address = "192.168.1.7",
                port = 53317,
                protocol = "https",
                alias = "unit",
                version = "2.0",
                deviceModel = null,
                deviceType = null,
                fingerprint = fingerprint,
                download = false
            )
        ))
}
