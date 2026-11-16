package dev.codesurgen.ruta

/*
 * Pure rules behind scooter pairing and first-run onboarding (no android.* imports, so test.sh
 * compiles them on the host): PIN and nickname validation, which advertisements are Hero TCUs,
 * signal bars, scan-list ordering and throttling, bond outcomes, pairing-variant names and the
 * onboarding step order. Platform code lives in Pairing.kt, SecureStore.kt and Onboarding.kt.
 */

/** One scooter on the Find scooter step. [rssi] is [RSSI_UNKNOWN] for a paired scooter not heard yet. */
data class ScanCandidate(val mac: String, val name: String, val rssi: Int, val bonded: Boolean) {
    /** 0..4 bars; 0 = not heard (paired, out of range or connected elsewhere). */
    val bars: Int get() = PairingRules.signalBars(rssi)

    companion object {
        const val RSSI_UNKNOWN = Int.MIN_VALUE
    }
}

/** How a bond attempt ended, from the ACTION_BOND_STATE_CHANGED states. */
enum class BondOutcome { PENDING, BONDED, WRONG_PIN, REJECTED }

object PairingRules {
    const val PIN_MIN = 4
    const val PIN_MAX = 8
    const val NICKNAME_MAX = 24
    const val DEFAULT_NICKNAME = "My scooter"
    /** Hero TCUs advertise as HMC_<digits>. */
    const val NAME_PREFIX = "HMC_"
    /** TCU info service (Tcu.INFO_SERVICE), matched when a scooter advertises it. */
    const val INFO_SERVICE_UUID = "1e5af1a3-fab3-4e33-aafb-331e781ed789"
    /** A scan stops by itself after this long. */
    const val SCAN_WINDOW_MS = 30_000L
    /** Signal-bar changes of a known scooter are published at most this often. */
    const val RSSI_THROTTLE_MS = 1_000L

    // android.bluetooth.BluetoothDevice values, repeated so this file stays pure.
    const val BOND_NONE = 10
    const val BOND_BONDING = 11
    const val BOND_BONDED = 12
    /** UNBOND_REASON_* values of the bond broadcast's (hidden) reason extra; -1 = absent. */
    const val UNBOND_AUTH_FAILED = 1
    const val UNBOND_AUTH_REJECTED = 2
    const val UNBOND_AUTH_CANCELED = 3
    const val UNBOND_REMOTE_AUTH_CANCELED = 8
    const val VARIANT_PIN = 0
    const val VARIANT_PASSKEY = 1
    const val VARIANT_PASSKEY_CONFIRMATION = 2
    const val VARIANT_CONSENT = 3
    const val VARIANT_DISPLAY_PASSKEY = 4
    const val VARIANT_DISPLAY_PIN = 5
    const val VARIANT_OOB_CONSENT = 6
    const val VARIANT_PIN_16_DIGITS = 7

    private val MAC = Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")

    /** Keeps digits only, at most [PIN_MAX] (what the PIN field accepts while typing or pasting). */
    fun sanitizePin(input: CharSequence?): String {
        if (input == null) return ""
        val out = StringBuilder(PIN_MAX)
        for (c in input) {
            if (c in '0'..'9') out.append(c)
            if (out.length == PIN_MAX) break
        }
        return out.toString()
    }

    /** 4–8 ASCII digits. */
    fun isValidPin(pin: String?): Boolean = pin != null && pin.length in PIN_MIN..PIN_MAX && pin.all { it in '0'..'9' }

    /** Trimmed nickname with inner whitespace collapsed, capped at [NICKNAME_MAX]; blank → [DEFAULT_NICKNAME]. */
    fun nickname(input: String?): String {
        val clean = input.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return DEFAULT_NICKNAME
        return if (clean.length <= NICKNAME_MAX) clean else clean.substring(0, NICKNAME_MAX).trimEnd()
    }

    /** "aa-bb-cc-dd-ee-ff" / "aabbccddeeff" / "AA:BB:…" → "AA:BB:CC:DD:EE:FF"; null when it is not a MAC. */
    fun normalizeMac(input: String?): String? {
        val hex = input?.trim()?.uppercase()?.filter { it in '0'..'9' || it in 'A'..'F' } ?: return null
        val raw = input.trim().uppercase().filterNot { it == ':' || it == '-' || it == ' ' }
        if (hex.length != 12 || raw.length != 12) return null
        val mac = hex.chunked(2).joinToString(":")
        return mac.takeIf { MAC.matches(it) }
    }

    /** A Hero TCU by name ("HMC_…") or by advertising the TCU info service. */
    fun isScooter(name: String?, serviceUuids: Collection<String> = emptyList()): Boolean =
        name.orEmpty().trim().startsWith(NAME_PREFIX, ignoreCase = true) ||
            serviceUuids.any { it.equals(INFO_SERVICE_UUID, ignoreCase = true) }

    /** dBm → 0..4 bars (0 only for [ScanCandidate.RSSI_UNKNOWN]; anything heard shows at least one). */
    fun signalBars(rssi: Int): Int = when {
        rssi == ScanCandidate.RSSI_UNKNOWN -> 0
        rssi >= -60 -> 4
        rssi >= -72 -> 3
        rssi >= -84 -> 2
        else -> 1
    }

    /** Spoken/tooltip word for [bars]. */
    fun signalWord(bars: Int): String = when (bars) {
        4 -> "Excellent signal"
        3 -> "Good signal"
        2 -> "Fair signal"
        1 -> "Weak signal"
        else -> "Not in range"
    }

    /**
     * Calm, stable order: paired scooters first, then the rest in the order they were found
     * (rows never jump around as signal strength changes).
     */
    fun ordered(found: Iterable<ScanCandidate>): List<ScanCandidate> {
        val bonded = ArrayList<ScanCandidate>(); val others = ArrayList<ScanCandidate>()
        for (c in found) if (c.bonded) bonded += c else others += c
        return bonded + others
    }

    /**
     * Bond outcome from one ACTION_BOND_STATE_CHANGED ([previous] → [current], [reason] = the
     * UNBOND_REASON_* extra or -1). BONDING → NONE with our PIN supplied means the scooter refused it,
     * unless Android says pairing was cancelled (by the rider in an OEM dialog, or by the scooter):
     * that is REJECTED, not a wrong PIN. Other reasons (e.g. the link dropping, which these scooters
     * do on a wrong PIN) keep the wrong-PIN rule. Without a PIN the rider cancelled or the system
     * dialog was rejected.
     */
    fun bondOutcome(previous: Int, current: Int, pinSupplied: Boolean, reason: Int = -1): BondOutcome = when (current) {
        BOND_BONDED -> BondOutcome.BONDED
        BOND_NONE -> when {
            !pinSupplied || previous != BOND_BONDING -> BondOutcome.REJECTED
            reason == UNBOND_AUTH_CANCELED || reason == UNBOND_REMOTE_AUTH_CANCELED -> BondOutcome.REJECTED
            else -> BondOutcome.WRONG_PIN
        }
        else -> BondOutcome.PENDING
    }

    /** Variants that ask for a typed PIN/passkey (setPin can answer [VARIANT_PIN] and [VARIANT_PIN_16_DIGITS]). */
    fun needsPin(variant: Int): Boolean = variant == VARIANT_PIN || variant == VARIANT_PIN_16_DIGITS || variant == VARIANT_PASSKEY

    /** setPin answers these silently (LE passkey entry arrives as a PIN request). */
    fun answerable(variant: Int): Boolean = variant == VARIANT_PIN || variant == VARIANT_PIN_16_DIGITS

    /** Log name of a pairing variant (never the PIN). */
    fun variantName(variant: Int): String = when (variant) {
        VARIANT_PIN -> "PIN"
        VARIANT_PASSKEY -> "passkey"
        VARIANT_PASSKEY_CONFIRMATION -> "passkey confirmation"
        VARIANT_CONSENT -> "consent"
        VARIANT_DISPLAY_PASSKEY -> "display passkey"
        VARIANT_DISPLAY_PIN -> "display PIN"
        VARIANT_OOB_CONSENT -> "OOB consent"
        VARIANT_PIN_16_DIGITS -> "16-digit PIN"
        else -> "variant $variant"
    }
}

/**
 * Scan results keyed by MAC with throttled publishing: a new scooter (or a change of name / paired
 * state) publishes at once; a signal-bar change publishes at most every [throttleMs] ([offer]
 * returns false and [due] reports it later); RSSI jitter within the same bar never republishes.
 */
class ScanAggregator(private val throttleMs: Long = PairingRules.RSSI_THROTTLE_MS) {
    private val byMac = LinkedHashMap<String, ScanCandidate>()
    private var dirty = false
    private var publishedAt = Long.MIN_VALUE / 2

    val size: Int get() = byMac.size

    /** Adds or updates [candidate]; true = publish [snapshot] now. */
    fun offer(candidate: ScanCandidate, now: Long): Boolean {
        val mac = PairingRules.normalizeMac(candidate.mac) ?: candidate.mac.uppercase()
        val old = byMac[mac]
        val merged = if (old == null) candidate.copy(mac = mac) else candidate.copy(
            mac = mac,
            name = candidate.name.ifBlank { old.name },
            rssi = if (candidate.rssi == ScanCandidate.RSSI_UNKNOWN) old.rssi else candidate.rssi,
            bonded = old.bonded || candidate.bonded
        )
        byMac[mac] = merged
        val structural = old == null || old.bonded != merged.bonded || old.name != merged.name
        if (structural) { publishedAt = now; dirty = false; return true }
        if (old.bars == merged.bars) return false
        dirty = true
        return due(now)
    }

    /** True (once) when a throttled change is waiting and may be published now. */
    fun due(now: Long): Boolean {
        if (!dirty || now - publishedAt < throttleMs) return false
        publishedAt = now; dirty = false
        return true
    }

    fun snapshot(): List<ScanCandidate> = PairingRules.ordered(byMac.values)

    fun clear() { byMac.clear(); dirty = false; publishedAt = Long.MIN_VALUE / 2 }
}

/** First-run / add-scooter steps, in order. */
enum class OnboardingStep { WELCOME, PERMISSIONS, FIND, PIN, CONNECTING, DONE }

/**
 * Step order (DESIGN Addendum C): Welcome → Permissions → Find → PIN (only when not paired) →
 * Connecting → Done. [addOnly] (More › Add scooter) starts at Find.
 */
object OnboardingPlan {
    fun first(addOnly: Boolean): OnboardingStep = if (addOnly) OnboardingStep.FIND else OnboardingStep.WELCOME

    fun steps(addOnly: Boolean, bonded: Boolean): List<OnboardingStep> = OnboardingStep.entries.filter {
        !(addOnly && (it == OnboardingStep.WELCOME || it == OnboardingStep.PERMISSIONS)) && !(bonded && it == OnboardingStep.PIN)
    }

    /** The step after [step]; DONE stays DONE. */
    fun next(step: OnboardingStep, addOnly: Boolean, bonded: Boolean): OnboardingStep {
        val list = steps(addOnly, bonded)
        val i = list.indexOf(step)
        return if (i < 0) first(addOnly) else list[minOf(i + 1, list.size - 1)]
    }

    /** The step back from [step]; null = leave the flow (first step, or nothing to go back to after DONE). */
    fun back(step: OnboardingStep, addOnly: Boolean, bonded: Boolean): OnboardingStep? {
        if (step == OnboardingStep.DONE) return null
        val list = steps(addOnly, bonded)
        val i = list.indexOf(step)
        return if (i <= 0) null else list[i - 1]
    }

    /** 0-based position of [step] and the number of steps (for the step indicator); DONE is not counted (shown as the last). */
    fun position(step: OnboardingStep, addOnly: Boolean, bonded: Boolean): Pair<Int, Int> {
        val list = steps(addOnly, bonded).filter { it != OnboardingStep.DONE }
        val index = if (step == OnboardingStep.DONE) list.size - 1 else list.indexOf(step).coerceAtLeast(0)
        return index to list.size
    }
}
