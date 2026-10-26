package com.rendyhd.vicu.speech

/** SHA-256 (FIPS 180-4) in common code, fed in pieces: the check of a downloaded speech model. */
class Sha256 {
    private val h = intArrayOf(
        0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
        0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt(),
    )
    private val block = ByteArray(64)
    private var blockLength = 0
    private var total = 0L
    private val w = IntArray(64)

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        var i = offset
        val end = offset + length
        total += length
        if (blockLength > 0) {
            while (i < end && blockLength < 64) block[blockLength++] = data[i++]
            if (blockLength == 64) {
                compress(block, 0)
                blockLength = 0
            }
        }
        while (end - i >= 64) {
            compress(data, i)
            i += 64
        }
        while (i < end) block[blockLength++] = data[i++]
    }

    fun digest(): ByteArray {
        val bits = total * 8
        val padding = ByteArray(if (blockLength < 56) 56 - blockLength else 120 - blockLength)
        padding[0] = 0x80.toByte()
        val savedTotal = total
        update(padding)
        val length = ByteArray(8) { (bits ushr (56 - 8 * it)).toByte() }
        update(length)
        total = savedTotal
        val out = ByteArray(32)
        for (k in 0 until 8) for (b in 0 until 4) out[k * 4 + b] = (h[k] ushr (24 - 8 * b)).toByte()
        return out
    }

    fun hexDigest(): String = digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun compress(data: ByteArray, offset: Int) {
        for (t in 0 until 16) {
            val j = offset + t * 4
            w[t] = (data[j].toInt() and 0xff shl 24) or (data[j + 1].toInt() and 0xff shl 16) or
                (data[j + 2].toInt() and 0xff shl 8) or (data[j + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }

    private companion object {
        val K = intArrayOf(
            0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b.toInt(), 0x59f111f1.toInt(),
            0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
            0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(), 0xe49b69c1.toInt(), 0xefbe4786.toInt(),
            0x0fc19dc6.toInt(), 0x240ca1cc.toInt(), 0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(),
            0x06ca6351.toInt(), 0x14292967.toInt(), 0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
            0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(), 0xa2bfe8a1.toInt(), 0xa81a664b.toInt(),
            0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
            0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(), 0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(),
            0x5b9cca4f.toInt(), 0x682e6ff3.toInt(), 0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}
