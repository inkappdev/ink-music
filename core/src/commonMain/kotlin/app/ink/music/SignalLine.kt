package app.ink.music

import kotlin.math.PI
import kotlin.math.sin

/**
 * The signal tile's line (NTS's mixtapes, 2026-11-24): its bars' heights, 0 to 1, drawn, not heard (neither app reads
 * the sound's levels). Still, a low rise and fall of its own for each mixtape; moving, slow swells passing through it,
 * a beat thumping the low end and a quicker flicker riding on top, as a level meter looks under music. Shared by both apps.
 */
object SignalLine {
    /** A bar's own small difference, the same each time: 0 to 1. */
    private fun grain(i: Int, seed: Int): Float {
        val v = sin(i * 12.9898 + seed * .0137) * 43758.5453
        return (v - kotlin.math.floor(v)).toFloat()
    }

    /** Bar [i] of [n] at rest: low, a gentle rise and fall. */
    fun still(i: Int, n: Int, seed: Int): Float {
        val x = i.toFloat() / n.coerceAtLeast(1)
        return .1f + .1f * (.5f + .5f * sin(x * PI * 3 + seed % 7).toFloat()) + .06f * grain(i, seed)
    }

    /** Bar [i] of [n], [t] seconds in, playing. */
    fun moving(i: Int, n: Int, seed: Int, t: Float): Float {
        val x = i.toFloat() / n.coerceAtLeast(1)
        val s = (seed % 97) * .1f
        val swell = (.5f + .5f * sin(2 * PI * (x * 1.3 - t * .35) + s).toFloat()) * (.55f + .45f * sin(2 * PI * (x * .6 + t * .21) + s * 2).toFloat())
        // a beat at about 118 a minute, falling away after each, the low end (the left) feeling it most
        val since = (t * 1.97f) % 1f
        val beat = (1 - since).let { it * it * it } * (1 - x * .7f)
        val flicker = (.5f + .5f * sin(t * 9.1f + i * 2.39f + s).toFloat()) * (.5f + .5f * sin(t * 5.3f + i * 1.7f).toFloat())
        return (.08f + .5f * swell + .28f * beat + .22f * flicker * (.4f + .6f * grain(i, seed))).coerceIn(.06f, 1f)
    }

    /** ∞ as Silkscreen would draw it (it has none of its own): seven pixels by five, a row each. */
    val INFINITY = listOf(".#...#.", "#.#.#.#", "#..#..#", "#.#.#.#", ".#...#.")
}
