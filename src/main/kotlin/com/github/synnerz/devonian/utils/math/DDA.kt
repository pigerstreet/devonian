package com.github.synnerz.devonian.utils.math

import net.minecraft.core.BlockPos
import java.util.*
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * WARNING: nextElement hands back the same mutable position every step, so it is only valid until
 * the next one. Anything that keeps a step past that must call `immutable()` on it - see
 * WorldUtils.raycast, the only caller. Ray marching allocated a BlockPos per block boundary
 * crossed otherwise - averaged over look directions a 57 block etherwarp ray crosses 88 of them,
 * and EtherwarpOverlay casts up to two rays a frame.
 */
class DDA(x: Double, y: Double, z: Double, dx: Double, dy: Double, dz: Double) :
    Enumeration<BlockPos> {
    private var x = floor(x).toInt()
    private var y = floor(y).toInt()
    private var z = floor(z).toInt()
    private val mag = sqrt(dx * dx + dy * dy + dz * dz)
    private val sx: Int
    private val sy: Int
    private val sz: Int
    private val tdx: Double
    private val tdy: Double
    private val tdz: Double
    private var tmx: Double
    private var tmy: Double
    private var tmz: Double

    private var t = 0.0

    init {
        val dx1 = dx / mag
        val dy1 = dy / mag
        val dz1 = dz / mag
        sx = sign(dx1).toInt()
        sy = sign(dy1).toInt()
        sz = sign(dz1).toInt()
        tdx = sx / dx1
        tdy = sy / dy1
        tdz = sz / dz1
        tmx = if (dx1 == 0.0) Double.POSITIVE_INFINITY else (this.x + (if (dx1 > 0.0) 1.0 else 0.0) - x) / dx1
        tmy = if (dy1 == 0.0) Double.POSITIVE_INFINITY else (this.y + (if (dy1 > 0.0) 1.0 else 0.0) - y) / dy1
        tmz = if (dz1 == 0.0) Double.POSITIVE_INFINITY else (this.z + (if (dz1 > 0.0) 1.0 else 0.0) - z) / dz1
    }

    override fun hasMoreElements(): Boolean {
        return t <= mag
    }

    private val cursor = BlockPos.MutableBlockPos()

    override fun nextElement(): BlockPos {
        val bp = cursor.set(x, y, z)

        if (tmx < tmy && tmx < tmz) {
            x += sx
            t = tmx
            tmx += tdx
        } else if (tmy < tmz) {
            y += sy
            t = tmy
            tmy += tdy
        } else {
            z += sz
            t = tmz
            tmz += tdz
        }

        return bp
    }
}