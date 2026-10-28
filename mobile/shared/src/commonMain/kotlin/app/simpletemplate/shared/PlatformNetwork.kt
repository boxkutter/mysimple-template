package app.simpletemplate.shared

/**
 * Development builds only: names under `.localhost` (the platform's development stack, e.g.
 * `api.mysimple.localhost`) resolve to this device's loopback, where `adb reverse` forwards
 * them to the developer's computer. Off in release builds.
 */
object PlatformNetwork {
    var resolveLocalhostToLoopback: Boolean = false
}
