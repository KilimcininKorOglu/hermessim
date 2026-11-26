// HermesSIM host. Copyright (C) 2026 Kerem Gök. GPL-3.0-or-later.
package com.keremgok.hermessim.host

/**
 * The card before the setup steps: what this phone becomes and what root costs. Shown once, on a phone that is not yet
 * rooted and not yet a host; a rooted phone or a running host is past the point where it could change the choice. Pure.
 */
object SetupIntro {
    fun shows(seen: Boolean, rooted: Boolean, host: Boolean): Boolean = !seen && !rooted && !host
}
