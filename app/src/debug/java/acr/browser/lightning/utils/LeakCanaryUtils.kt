package acr.browser.lightning.utils

import javax.inject.Inject

/**
 * No-op debug utility. LeakCanary is intentionally excluded from personal builds.
 */
class LeakCanaryUtils @Inject constructor() {

    /**
     * No-op retained to keep debug and release source sets compatible.
     */
    fun setup() = Unit
}
