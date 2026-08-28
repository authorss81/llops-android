package android.os

/**
 * TEST-ONLY shadow of android.os.Build.
 *
 * ⚠ FOOT-GUN: this file shadows `android.os.Build` for the ENTIRE unit test
 * source set. ANY test that reads a real Build field (SDK_INT, VERSION_RELEASE,
 * MANUFACTURER, …) in production-code branches gets these baked-in values
 * instead — e.g. SDK_INT is pinned to 36 here, so a fallback branch guarded by
 * `Build.VERSION.SDK_INT < TIRAMISU` is never exercised. Do NOT add fields for
 * mere convenience and never let production logic decisions be tested against
 * this fake as if it were the real device. If a new SDK release adds a field
 * that must be present for a compile or a test, mirror it here AND on
 * `_Original_Build`'s matching shape (see below) — keeping the two in sync is
 * the whole contract of this file. Prefer asserting on observable outcomes
 * instead of reading Build.
 *
 * Paparazzi 2.0.0-alpha01's Renderer.configureBuildProperties() reflects over
 * the test classpath's `android.os.Build` and requires (a) every nested class
 * of Build to ALSO exist on layoutlib's `_Original_Build` (a `.single{}` lookup
 * that throws `NoSuchElementException` otherwise), and (b) every field copied
 * to have the SAME type as the matching `_Original_Build` field (else a
 * `ClassCastException`).
 *
 * With compileSdk 36 the mockable android.jar adds `VERSION_CODES_FULL` (API
 * 36), but the bundled layoutlib-15.1.4 `_Original_Build` predates it and tops
 * out at `VANILLA_ICE_CREAM`. This shadow mirrors `_Original_Build`'s exact
 * field names + types (so the reflective value copy never casts) and omits
 * `VERSION_CODES_FULL`, letting Paparazzi render on the Kotlin 2.0.21 toolchain.
 *
 * Values are irrelevant: configureBuildProperties overwrites every field from
 * `_Original_Build` at prepare() time, and render-side constants are inlined at
 * compile time, so no runtime Build read is needed.
 *
 * @JvmField compiles members to real `public static final` fields on each
 * nested class (matching Java reflection), not Kotlin companion-object fields.
 */
object Build {
    @JvmField val UNKNOWN: String = "unknown"
    @JvmField val ID: String = ""
    @JvmField val DISPLAY: String = ""
    @JvmField val PRODUCT: String = "unknown"
    @JvmField val PRODUCT_FOR_ATTESTATION: String = ""
    @JvmField val DEVICE: String = ""
    @JvmField val DEVICE_FOR_ATTESTATION: String = ""
    @JvmField val BOARD: String = ""
    @JvmField val CPU_ABI: String = ""
    @JvmField val CPU_ABI2: String = ""
    @JvmField val MANUFACTURER: String = "unknown"
    @JvmField val MANUFACTURER_FOR_ATTESTATION: String = ""
    @JvmField val BRAND: String = ""
    @JvmField val BRAND_FOR_ATTESTATION: String = ""
    @JvmField val MODEL: String = "unknown"
    @JvmField val MODEL_FOR_ATTESTATION: String = ""
    @JvmField val SOC_MANUFACTURER: String = ""
    @JvmField val SOC_MODEL: String = ""
    @JvmField val BOOTLOADER: String = ""
    @JvmField val RADIO: String = ""
    @JvmField val HARDWARE: String = ""
    @JvmField val SKU: String = ""
    @JvmField val ODM_SKU: String = ""
    @JvmField val IS_EMULATOR: Boolean = false
    @JvmField val SERIAL: String = "unknown"
    @JvmField val SUPPORTED_ABIS: Array<String> = emptyArray()
    @JvmField val SUPPORTED_32_BIT_ABIS: Array<String> = emptyArray()
    @JvmField val SUPPORTED_64_BIT_ABIS: Array<String> = emptyArray()
    @JvmField val VENDOR_API_2024_Q2: Int = 0
    @JvmField val TYPE: String = ""
    @JvmField val TAGS: String = ""
    @JvmField val FINGERPRINT: String = ""
    @JvmField val HW_TIMEOUT_MULTIPLIER: Int = 1
    @JvmField val IS_TREBLE_ENABLED: Boolean = false
    @JvmField val TIME: Long = 0L
    @JvmField val USER: String = ""
    @JvmField val HOST: String = ""
    @JvmField val IS_DEBUGGABLE: Boolean = false
    @JvmField val IS_ENG: Boolean = false
    @JvmField val IS_USERDEBUG: Boolean = false
    @JvmField val IS_USER: Boolean = true
    @JvmField val IS_ARC: Boolean = false
    @JvmField val PERMISSIONS_REVIEW_REQUIRED: Boolean = false

    object VERSION {
        @JvmField val INCREMENTAL: String = ""
        @JvmField val RELEASE: String = "36"
        @JvmField val RELEASE_OR_CODENAME: String = "REL"
        @JvmField val RELEASE_OR_PREVIEW_DISPLAY: String = "36"
        @JvmField val BASE_OS: String = ""
        @JvmField val SECURITY_PATCH: String = ""
        @JvmField val MEDIA_PERFORMANCE_CLASS: Int = 0
        @JvmField val SDK: String = "36"
        @JvmField val SDK_INT: Int = 36
        @JvmField val SDK_MINOR_INT: Int = 0
        @JvmField val DEVICE_INITIAL_SDK_INT: Int = 26
        @JvmField val PREVIEW_SDK_INT: Int = 0
        @JvmField val PREVIEW_SDK_FINGERPRINT: String = ""
        @JvmField val CODENAME: String = "REL"
        @JvmField val KNOWN_CODENAMES: Set<String> = emptySet()
        @JvmField val ACTIVE_CODENAMES: Array<String> = emptyArray()
        @JvmField val RESOURCES_SDK_INT: Int = 36
        @JvmField val MIN_SUPPORTED_TARGET_SDK_INT: Int = 0
    }

    object VERSION_CODES {
        @JvmField val CUR_DEVELOPMENT: Int = 10000
        @JvmField val BASE: Int = 1
        @JvmField val BASE_1_1: Int = 2
        @JvmField val CUPCAKE: Int = 3
        @JvmField val DONUT: Int = 4
        @JvmField val ECLAIR: Int = 5
        @JvmField val ECLAIR_0_1: Int = 6
        @JvmField val ECLAIR_MR1: Int = 7
        @JvmField val FROYO: Int = 8
        @JvmField val GINGERBREAD: Int = 9
        @JvmField val GINGERBREAD_MR1: Int = 10
        @JvmField val HONEYCOMB: Int = 11
        @JvmField val HONEYCOMB_MR1: Int = 12
        @JvmField val HONEYCOMB_MR2: Int = 13
        @JvmField val ICE_CREAM_SANDWICH: Int = 14
        @JvmField val ICE_CREAM_SANDWICH_MR1: Int = 15
        @JvmField val JELLY_BEAN: Int = 16
        @JvmField val JELLY_BEAN_MR1: Int = 17
        @JvmField val JELLY_BEAN_MR2: Int = 18
        @JvmField val KITKAT: Int = 19
        @JvmField val KITKAT_WATCH: Int = 20
        @JvmField val L: Int = 21
        @JvmField val LOLLIPOP: Int = 21
        @JvmField val LOLLIPOP_MR1: Int = 22
        @JvmField val M: Int = 23
        @JvmField val N: Int = 24
        @JvmField val N_MR1: Int = 25
        @JvmField val O: Int = 26
        @JvmField val O_MR1: Int = 27
        @JvmField val P: Int = 28
        @JvmField val Q: Int = 29
        @JvmField val R: Int = 30
        @JvmField val S: Int = 31
        @JvmField val S_V2: Int = 32
        @JvmField val TIRAMISU: Int = 33
        @JvmField val UPSIDE_DOWN_CAKE: Int = 34
        @JvmField val VANILLA_ICE_CREAM: Int = 35
    }

    object Partition {
        @JvmField val PARTITION_NAME_SYSTEM: String = "system"
        @JvmField val PARTITION_NAME_BOOTIMAGE: String = "bootimage"
        @JvmField val PARTITION_NAME_ODM: String = "odm"
        @JvmField val PARTITION_NAME_OEM: String = "oem"
        @JvmField val PARTITION_NAME_PRODUCT: String = "product"
        @JvmField val PARTITION_NAME_SYSTEM_EXT: String = "system_ext"
        @JvmField val PARTITION_NAME_VENDOR: String = "vendor"
    }
}
