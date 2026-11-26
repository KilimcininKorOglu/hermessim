package com.keremgok.hermessim.host

/**
 * The paired phone's Bluetooth link is up and the phone says no Hello: the rules of the steps that bring it back
 * (Relay.helpSilent). Pure, so the JVM tests check them.
 */
object SilentLinkCore {
    /** A paired phone's link that is up this long without a Hello is silent: a Hello normally comes within a second or two. */
    const val SILENT_MS = 5000L

    /** The steps, in order; each only when the one before brought no Hello. [wire]: its name in logs and reports. */
    enum class Step(val wire: String) {
        SERVICES_CHANGED("services changed"),
        SERVICES_CHANGED_REFUSED("services changed (refused)"),
        DROP("drop"),
        BLUETOOTH_RESET("Bluetooth reset"),
    }

    /**
     * The steps run only for a phone that Android holds pairing keys for, and never with the pairing window open: a
     * phone that is pairing may be in its system's pairing dialog and cannot say Hello before that is done.
     */
    fun mayHelp(stillSilent: Boolean, pairingOpen: Boolean, bonded: Boolean): Boolean = stillSilent && !pairingOpen && bonded

    /**
     * The step a Hello followed, else null. Telling the phone acts on the same link; a drop or a Bluetooth reset acts
     * on the very next link. A Hello on a later link came by itself. [stepLink] and [helloLink] count the phone's links.
     */
    fun recoveredBy(step: Step?, stepLink: Int, helloLink: Int): Step? = when (step) {
        null -> null
        Step.SERVICES_CHANGED, Step.SERVICES_CHANGED_REFUSED -> step.takeIf { stepLink == helloLink }
        Step.DROP, Step.BLUETOOTH_RESET -> step.takeIf { stepLink + 1 == helloLink }
    }

    /** The log note for the place of our service: where it is now and where it was at the phone's last Hello. */
    fun placeNote(place: Int, placeAtHello: Int): String =
        if (placeAtHello != 0 && placeAtHello != place) " at place $place (the phone last said Hello with it at $placeAtHello)"
        else " at place $place"
}
