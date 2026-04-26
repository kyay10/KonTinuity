package io.github.kyay10.kontinuity

import kotlin.math.max
import kotlin.test.Test
import kotlinx.collections.immutable.toPersistentHashMap

enum class Bit {
  Zero,
  One,
}

operator fun Bit.plus(other: Int) = this.ordinal + other

operator fun Int.plus(other: Bit) = this + other.ordinal

operator fun Bit.plus(other: Bit) = this.ordinal + other.ordinal

operator fun Bit.times(other: Int) = this.ordinal * other

operator fun Int.times(other: Bit) = this * other.ordinal

operator fun Bit.times(other: Bit) = this.ordinal * other.ordinal

typealias Baire = suspend (Int) -> Int

typealias Cantor = suspend (Int) -> Bit

class ImpossibleTest {
  // From https://math.andrej.com/2011/12/06/how-to-make-the-impossible-functionals-run-even-faster/
  @Test
  fun videoExamples() = runTestCC {
    mu({ a -> a(a(10)) }) { n -> n * n } shouldEq 100
    findNeighborhood { it(10) != it(11) } shouldEq mapOf(10 to Bit.One, 11 to Bit.Zero)
    findNeighborhood { it(11) != it(10) } shouldEq mapOf(10 to Bit.Zero, 11 to Bit.One)
    forAll { it(10) == Bit.Zero || it(10) == Bit.One } shouldEq true
    exists { it(10) == Bit.Zero && it(10) != Bit.Zero } shouldEq false
  }

  // https://math.andrej.com/2007/09/28/seemingly-impossible-functional-programs/
  @Test
  fun articleExamples() = runTestCC {
    suspend fun f(a: Cantor): Bit = a(7 * a(4) + 4 * a(7) + 4)
    suspend fun g(a: Cantor): Bit = a(a(4) + 11 * a(7))
    suspend fun h(a: Cantor): Bit =
      when {
        a(7) == Bit.Zero && a(4) == Bit.Zero -> a(4)
        a(7) == Bit.Zero -> a(11)
        a(4) == Bit.One -> a(15)
        else -> a(8)
      }
    ::f cantorEq ::g shouldEq false
    ::f cantorEq ::h shouldEq true
    ::g cantorEq ::h shouldEq false
    ::f cantorEq ::f shouldEq true
    ::g cantorEq ::g shouldEq true
    ::h cantorEq ::h shouldEq true

    modulus { 45000 } shouldEq 0
    for (i in 1..10) {
      modulus { it.project(i) } shouldEq (i + 1)
    }

    suspend fun element10(a: Cantor) = a project 10
    suspend fun element1000(a: Cantor) = a project 1_000

    ::element1000 cantorEq ::element1000 shouldEq true
    ::element1000 cantorEq { it project 4_000 } shouldEq false
    ::element1000 cantorEq { it project 0x200000 } shouldEq false
    ::element10 cantorEq ::element10 shouldEq true
    ::element10 cantorEq { it project 15 } shouldEq false
    ::element10 cantorEq { it project 20 } shouldEq false
    ::element10 cantorEq { it project 200 } shouldEq false
    ::element10 cantorEq { it project 2_000 } shouldEq false
    ::element10 cantorEq { it project 20_000 } shouldEq false

    // TODO use BigInt for better flexing!
    suspend fun elementMaxInt(a: Cantor) = a project Int.MAX_VALUE
    ::elementMaxInt cantorEq ::elementMaxInt shouldEq true
    ::elementMaxInt cantorEq { it project (Int.MAX_VALUE - 1) } shouldEq false
  }

  @Test
  fun ensureBacktracking() = runTestCC {
    // In a bad implementation, either `it(1)` or `it(0) will be forced to a bad value
    for (it0 in Bit.entries) {
      for (it1 in Bit.entries) {
        findNeighborhood {
          val _ = it(0)
          val _ = it(1)
          it(0) == it0 && it(1) == it1
        } shouldEq mapOf(0 to it0, 1 to it1)
      }
    }
  }
}

/** @return k s.t. `forall beta: Baire. beta(0..k) = alpha(0..k) => f(beta) = f(alpha)` */
private suspend fun mu(f: suspend (Baire) -> Int, alpha: Baire): Int =
  runState(0) {
    val _ = f { n ->
      value = max(value, n)
      alpha(n)
    }
    value
  }

private suspend fun findNeighborhood(predicate: suspend (Cantor) -> Boolean): Map<Int, Bit> = buildMapLocally {
  // TODO this gives us `exists` for free!
  val _ = handle {
    predicate { i ->
      get(i)
        ?: use { k ->
            val current = map.toPersistentHashMap()
            k(Bit.One) ||
              run {
                // reset state
                clear()
                putAll(current)
                k(Bit.Zero)
              }
          }
          .also { set(i, it) }
    }
  }
}

private suspend fun epsilon(predicate: suspend (Cantor) -> Boolean): Cantor {
  val neighborhood = findNeighborhood(predicate)
  return { i -> neighborhood[i] ?: Bit.One }
}

private suspend fun exists(predicate: suspend (Cantor) -> Boolean): Boolean = predicate(epsilon(predicate))

private suspend fun forAll(predicate: suspend (Cantor) -> Boolean): Boolean = !exists { !predicate(it) }

private suspend infix fun <T> (suspend (Cantor) -> T).cantorEq(other: suspend (Cantor) -> T): Boolean = forAll { a ->
  this(a) == other(a)
}

private suspend fun modulus(f: suspend (Cantor) -> Int): Int = least { n ->
  forAll { a -> forAll { b -> eq(n, a, b) implies (f(a) == f(b)) } }
}

private inline fun least(p: (Int) -> Boolean): Int {
  var n = 0
  while (!p(n)) n++
  return n
}

private suspend fun eq(n: Int, a: Cantor, b: Cantor): Boolean {
  for (i in 0 until n) {
    if (a(i) != b(i)) return false
  }
  return true
}

private infix fun Boolean.implies(other: Boolean): Boolean = !this || other

private suspend infix fun Cantor.project(k: Int) = this(k).ordinal
