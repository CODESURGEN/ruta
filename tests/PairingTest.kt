package dev.codesurgen.ruta

// Synthetic identifiers only (open-source rule): HMC_000000, 00:11:22:33:44:55.

fun main() {
    pins()
    nicknames()
    macs()
    scooterFilter()
    signal()
    aggregator()
    bonds()
    variants()
    plan()
    println("PairingTest: all checks passed")
}

private fun pins() {
    check(PairingRules.sanitizePin(" 12-34 56 ") == "123456")
    check(PairingRules.sanitizePin("1234567890") == "12345678")
    check(PairingRules.sanitizePin(null) == "" && PairingRules.sanitizePin("abc") == "")
    check(PairingRules.sanitizePin("١٢٣٤") == "") // Only ASCII digits reach setPin.
    check(PairingRules.isValidPin("1234") && PairingRules.isValidPin("12345678") && PairingRules.isValidPin("000000"))
    check(!PairingRules.isValidPin("123") && !PairingRules.isValidPin("123456789"))
    check(!PairingRules.isValidPin("12a4") && !PairingRules.isValidPin("") && !PairingRules.isValidPin(null))
    check(!PairingRules.isValidPin("12 34"))
}

private fun nicknames() {
    check(PairingRules.nickname(null) == "My scooter" && PairingRules.nickname("   ") == "My scooter")
    check(PairingRules.nickname("  Blue   one ") == "Blue one")
    val long = PairingRules.nickname("A very long scooter nickname indeed")
    check(long.length <= PairingRules.NICKNAME_MAX && long == "A very long scooter nick")
    check(PairingRules.nickname("x".repeat(30)).length == 24)
}

private fun macs() {
    check(PairingRules.normalizeMac("00:11:22:33:44:55") == "00:11:22:33:44:55")
    check(PairingRules.normalizeMac("aa-bb-cc-dd-ee-ff") == "AA:BB:CC:DD:EE:FF")
    check(PairingRules.normalizeMac("aabbccddeeff") == "AA:BB:CC:DD:EE:FF")
    check(PairingRules.normalizeMac(" 00:11:22:33:44:5 ") == null)
    check(PairingRules.normalizeMac("00:11:22:33:44:GG") == null)
    check(PairingRules.normalizeMac("00:11:22:33:44:55:66") == null)
    check(PairingRules.normalizeMac(null) == null && PairingRules.normalizeMac("") == null)
}

private fun scooterFilter() {
    check(PairingRules.isScooter("HMC_000000") && PairingRules.isScooter(" hmc_000000"))
    check(!PairingRules.isScooter("HMC") && !PairingRules.isScooter("Galaxy Buds") && !PairingRules.isScooter(null))
    check(PairingRules.isScooter(null, listOf("1E5AF1A3-FAB3-4E33-AAFB-331E781ED789")))
    check(!PairingRules.isScooter("", listOf("0000180f-0000-1000-8000-00805f9b34fb")))
}

private fun signal() {
    check(PairingRules.signalBars(ScanCandidate.RSSI_UNKNOWN) == 0)
    check(PairingRules.signalBars(-40) == 4 && PairingRules.signalBars(-60) == 4)
    check(PairingRules.signalBars(-61) == 3 && PairingRules.signalBars(-72) == 3)
    check(PairingRules.signalBars(-80) == 2 && PairingRules.signalBars(-95) == 1 && PairingRules.signalBars(-127) == 1)
    check(PairingRules.signalWord(4) == "Excellent signal" && PairingRules.signalWord(0) == "Not in range")
}

private fun aggregator() {
    val a = ScanAggregator(throttleMs = 1_000)
    // New scooter publishes at once; paired ones sort first, the rest keep discovery order.
    check(a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -70, false), 0))
    check(a.offer(ScanCandidate("00:11:22:33:44:66", "HMC_000001", -50, false), 10))
    check(a.offer(ScanCandidate("00:11:22:33:44:77", "HMC_000002", ScanCandidate.RSSI_UNKNOWN, true), 20))
    check(a.snapshot().map { it.name } == listOf("HMC_000002", "HMC_000000", "HMC_000001"))
    // Jitter inside the same bar: stored, never published.
    check(!a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -71, false), 30))
    check(!a.due(5_000))
    // A bar change right after a publish waits for the throttle window…
    check(!a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -50, false), 500))
    check(!a.due(900))
    check(a.due(1_100) && !a.due(1_200))
    check(a.snapshot().first { it.name == "HMC_000000" }.bars == 4)
    // …and publishes at once when the window has passed.
    check(a.offer(ScanCandidate("00:11:22:33:44:55", "HMC_000000", -90, false), 3_000))
    // Lower-case MACs merge with upper-case ones; an advert without a name keeps the known name;
    // a paired scooter heard over the air keeps its paired flag and gains a signal.
    check(a.offer(ScanCandidate("00:11:22:33:44:77", "", -55, false), 3_100) == false)
    val paired = a.snapshot().first()
    check(paired.name == "HMC_000002" && paired.bonded && paired.rssi == -55)
    check(!a.offer(ScanCandidate("00:11:22:33:44:66".lowercase(), "HMC_000001", -52, false), 3_200))
    check(a.size == 3)
    a.clear()
    check(a.size == 0 && a.snapshot().isEmpty())
}

private fun bonds() {
    val r = PairingRules
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_BONDED, true) == BondOutcome.BONDED)
    check(r.bondOutcome(r.BOND_NONE, r.BOND_BONDED, false) == BondOutcome.BONDED)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true) == BondOutcome.WRONG_PIN)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, false) == BondOutcome.REJECTED)
    check(r.bondOutcome(r.BOND_NONE, r.BOND_NONE, true) == BondOutcome.REJECTED)
    check(r.bondOutcome(r.BOND_NONE, r.BOND_BONDING, true) == BondOutcome.PENDING)
    // Unbond reason: auth failed / rejected = wrong PIN; cancelled = not a PIN problem; unknown keeps the rule.
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, r.UNBOND_AUTH_FAILED) == BondOutcome.WRONG_PIN)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, r.UNBOND_AUTH_REJECTED) == BondOutcome.WRONG_PIN)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, r.UNBOND_AUTH_CANCELED) == BondOutcome.REJECTED)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, r.UNBOND_REMOTE_AUTH_CANCELED) == BondOutcome.REJECTED)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, true, 4) == BondOutcome.WRONG_PIN)
    check(r.bondOutcome(r.BOND_BONDING, r.BOND_NONE, false, r.UNBOND_AUTH_FAILED) == BondOutcome.REJECTED)
}

private fun variants() {
    val r = PairingRules
    check(r.answerable(r.VARIANT_PIN) && r.answerable(r.VARIANT_PIN_16_DIGITS) && !r.answerable(r.VARIANT_PASSKEY))
    check(r.needsPin(r.VARIANT_PASSKEY) && !r.needsPin(r.VARIANT_PASSKEY_CONFIRMATION) && !r.needsPin(r.VARIANT_CONSENT))
    check(r.variantName(r.VARIANT_PIN) == "PIN" && r.variantName(42) == "variant 42")
}

private fun plan() {
    val W = OnboardingStep.WELCOME; val P = OnboardingStep.PERMISSIONS; val F = OnboardingStep.FIND
    val N = OnboardingStep.PIN; val C = OnboardingStep.CONNECTING; val D = OnboardingStep.DONE
    check(OnboardingPlan.first(false) == W && OnboardingPlan.first(true) == F)
    check(OnboardingPlan.steps(false, false) == listOf(W, P, F, N, C, D))
    check(OnboardingPlan.steps(false, true) == listOf(W, P, F, C, D))
    check(OnboardingPlan.steps(true, false) == listOf(F, N, C, D))
    check(OnboardingPlan.next(F, false, true) == C && OnboardingPlan.next(F, false, false) == N)
    check(OnboardingPlan.next(D, false, false) == D && OnboardingPlan.next(N, true, true) == F)
    check(OnboardingPlan.back(W, false, false) == null && OnboardingPlan.back(F, true, false) == null)
    check(OnboardingPlan.back(C, false, false) == N && OnboardingPlan.back(C, false, true) == F)
    check(OnboardingPlan.back(D, false, false) == null && OnboardingPlan.back(P, false, false) == W)
    check(OnboardingPlan.position(W, false, false) == (0 to 5) && OnboardingPlan.position(C, false, true) == (3 to 4))
    check(OnboardingPlan.position(F, true, false) == (0 to 3) && OnboardingPlan.position(D, true, false) == (2 to 3))
}
