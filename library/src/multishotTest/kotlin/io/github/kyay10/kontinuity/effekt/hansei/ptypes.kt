package io.github.kyay10.kontinuity.effekt.hansei

import org.kotlincrypto.hash.md.MD5

typealias Prob = Double

data class Probable<out A>(val prob: Prob, val value: A)
typealias Dist<A> = List<Probable<A>> // probability distribution (adds up to 1.0)
typealias CDist<A> = List<Probable<A>> // conditional probability distribution (not necessarily adds up to 1.0)

sealed class Value<out A> {
  data class Leaf<out A>(val value: A) : Value<A>()
  data class Branch<out A>(val searchTree: suspend () -> SearchTree<A>) : Value<A>()
}

typealias SearchTree<A> = List<Probable<Value<A>>>
typealias Selector<A> = suspend (CDist<A>) -> Probable<A>

// https://github.com/ocaml/stdlib-random/blob/main/random4/random4.ml
// Probably doesn't need Longs, but if it ain't broke, don't fix it
class OcamlRandom private constructor(private val state: LongArray, private var index: Int) {
  constructor() : this(LongArray(55) { 0 }, 0)
  constructor(seed: LongArray) : this() {
    fullInit(seed)
  }

  constructor(seed: Long) : this(longArrayOf(seed))

  private val md5 = MD5()

  private fun combine(accu: ByteArray, x: Long): ByteArray = md5.digest((accu + x.toString().encodeToByteArray()))
  private fun extract(d: ByteArray) =
    (d[0].toUByte().toLong() + (d[1].toUByte().toLong() shl 8) + (d[2].toUByte().toLong() shl 16) +
      (d[3].toUByte().toLong() shl 24))

  fun reinit(seed: Long) {
    fullInit(longArrayOf(seed))
  }

  internal fun fullInit(seed: LongArray) {
    val effectiveSeed = if (seed.isEmpty()) longArrayOf(0) else seed
    val seedLength = effectiveSeed.size

    for (i in 0..54) {
      state[i] = i.toLong()
    }

    var accu = byteArrayOf('x'.code.toByte())
    for (i in 0..(54 + maxOf(55, seedLength))) {
      val j = i % 55
      val k = i % seedLength
      accu = combine(accu, effectiveSeed[k])
      state[j] = (state[j] xor extract(accu)) and 0x3FFFFFFF
    }

    index = 0
  }

  // 30 random bits as the lower part of an integer
  private fun bits(): Int {
    index = (index + 1) % 55
    val curVal = state[index]
    val newVal = state[(index + 24) % 55] + (curVal xor ((curVal shr 25) and 0x1F))
    val newVal30 = newVal and 0x3FFFFFFF
    state[index] = newVal30
    return newVal30.toInt()
  }

  fun nextDouble(): Double {
    val scale = 1073741824.0
    val r1 = bits().toDouble()
    val r2 = bits().toDouble()
    return (r1 / scale + r2) / scale
  }
}

// https://github.com/ocaml/stdlib-random/blob/main/random3/random3.ml
// Probably doesn't need Longs, but if it ain't broke, don't fix it
class OcamlRandom3 private constructor(private val state: LongArray, private var index: Int) {
  constructor() : this(LongArray(55) { 0 }, 0)
  constructor(seed: LongArray) : this() {
    fullInit(seed)
  }

  constructor(seed: Long) : this(longArrayOf(seed))

  private val md5 = MD5()

  private fun combine(accu: ByteArray, x: Long): ByteArray = md5.digest((accu + x.toString().encodeToByteArray()))
  private fun extract(d: ByteArray) =
    (d[0].toUByte().toLong() + (d[1].toUByte().toLong() shl 8) + (d[2].toUByte().toLong() shl 16)) xor (d[3].toUByte()
      .toLong() shl 22)

  internal fun fullInit(seed: LongArray) {
    val effectiveSeed = if (seed.isEmpty()) longArrayOf(0) else seed
    val seedLength = effectiveSeed.size

    for (i in 0..54) {
      state[i] = i.toLong()
    }

    var accu = byteArrayOf('x'.code.toByte())
    for (i in 0..(54 + maxOf(55, seedLength))) {
      val j = i % 55
      val k = i % seedLength
      accu = combine(accu, effectiveSeed[k])
      state[j] = state[j] xor extract(accu)
    }

    index = 0
  }

  // 30 random bits as the lower part of an integer
  private fun bits(): Int {
    index = (index + 1) % 55
    val curVal = state[index]
    val newVal = state[(index + 24) % 55] + (curVal)
    val newVal30 = newVal and 0x3FFFFFFF
    state[index] = newVal30
    return newVal30.toInt()
  }

  fun nextDouble(): Double {
    val scale = 1073741824.0
    val r0 = bits().toDouble()
    val r1 = bits().toDouble()
    val r2 = bits().toDouble()
    return ((r0 / scale + r1) / scale + r2) / scale
  }
}

// https://github.com/ocaml/stdlib-random/blob/main/random3/random3.ml
// Probably doesn't need Longs, but if it ain't broke, don't fix it
class OcamlRandom3a private constructor(private val state: LongArray, private var index: Int) {
  constructor() : this(LongArray(55) { 0 }, 0)

  constructor(seed: Long) : this() {
    init(seed)
  }

  private val md5 = MD5()

  internal fun init(seed: Long) {
    var seed = seed
    fun mdg(): Long {
      seed++
      val d = md5.digest((seed.toString()).encodeToByteArray())
      return (d[0].toUByte().toLong() + (d[1].toUByte().toLong() shl 8) + (d[2].toUByte().toLong() shl 16)) xor (d[3]
        .toUByte().toLong() shl 22)
    }
    for (i in 0..54) {
      state[i] = mdg()
    }
    index = 0
  }

  // 30 random bits as the lower part of an integer
  private fun bits(): Int {
    index = (index + 1) % 55
    val curVal = state[index]
    val newVal = state[(index + 24) % 55] + (curVal)
    state[index] = newVal
    return (newVal and 0x3FFFFFFF).toInt()
  }

  fun nextDouble(): Double {
    val scale = 1073741824.0
    val r0 = bits().toDouble()
    val r1 = bits().toDouble()
    val r2 = bits().toDouble()
    return ((r0 / scale + r1) / scale + r2) / scale
  }
}

fun random(seed: Long) = OcamlRandom(seed)