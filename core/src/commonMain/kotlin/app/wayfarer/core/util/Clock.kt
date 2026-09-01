package app.wayfarer.core.util

/**
 * Wall clock in seconds since the epoch — the unit every nostr `created_at` uses.
 *
 * There is no default implementation on purpose: `commonMain` here has no
 * dependency that can read a wall clock without an opt-in or an extra library,
 * and injecting it keeps every time-dependent path testable. The app supplies
 * one at wiring time.
 */
fun interface Clock {
    fun nowSeconds(): Long
}
