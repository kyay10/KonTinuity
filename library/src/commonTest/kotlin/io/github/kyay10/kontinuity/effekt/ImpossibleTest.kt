package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*
import io.github.kyay10.kontinuity.effekt.Bit.One
import io.github.kyay10.kontinuity.effekt.Bit.Zero
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentHashMapOf
import kotlin.math.max
import kotlin.test.Test

enum class Bit { Zero, One }

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
    mu({ a -> a(a(10)) }) { n -> n * n } shouldBe 100
    findNeighborhood { it(10) != it(11) } shouldBe mapOf(10 to One, 11 to Zero)
    findNeighborhood { it(11) != it(10) } shouldBe mapOf(10 to Zero, 11 to One)
    forAll { it(10) == Zero || it(10) == One } shouldBe true
    exists { it(10) == Zero && it(10) != Zero } shouldBe false
  }

  //https://math.andrej.com/2007/09/28/seemingly-impossible-functional-programs/
  @Test
  fun articleExamples() = runTestCC {
    suspend fun f(a: Cantor): Bit = a(7 * a(4) +  4 * a(7) + 4)
    suspend fun g(a: Cantor): Bit = a(a(4) + 11 * a(7))
    suspend fun h(a: Cantor): Bit = when {
      a(7) == Zero && a(4) == Zero -> a(4)
      a(7) == Zero -> a(11)
      a(4) == One -> a(15)
      else -> a(8)
    }
    ::f cantorEq ::g shouldBe false
    ::f cantorEq ::h shouldBe true
    ::g cantorEq ::h shouldBe false
    ::f cantorEq ::f shouldBe true
    ::g cantorEq ::g shouldBe true
    ::h cantorEq ::h shouldBe true

    modulus { 45000 } shouldBe 0
    for (i in 1..10) {
      modulus { it.project(i) } shouldBe (i + 1)
    }

    suspend fun element10(a: Cantor) = a project 10
    suspend fun element1000(a: Cantor) = a project 1_000

    ::element1000 cantorEq ::element1000 shouldBe true
    ::element1000 cantorEq { it project 4_000 } shouldBe false
    ::element1000 cantorEq { it project 0x200000 } shouldBe false
    ::element10 cantorEq ::element10 shouldBe true
    ::element10 cantorEq { it project 15 } shouldBe false
    ::element10 cantorEq { it project 20 } shouldBe false
    ::element10 cantorEq { it project 200 } shouldBe false
    ::element10 cantorEq { it project 2_000 } shouldBe false
    ::element10 cantorEq { it project 20_000 } shouldBe false

    // TODO use BigInt for better flexing!
    suspend fun elementMaxInt(a: Cantor) = a project Int.MAX_VALUE
    ::elementMaxInt cantorEq ::elementMaxInt shouldBe true
    ::elementMaxInt cantorEq { it project (Int.MAX_VALUE - 1) } shouldBe false

  }

  @Test
  fun ensureBacktracking() = runTestCC {
    // In a bad implementation, either `it(1)` or `it(0) will be forced to a bad value
    for (it0 in Bit.entries) {
      for (it1 in Bit.entries) {
        findNeighborhood {
          it(0)
          it(1)
          it(0) == it0 && it(1) == it1
        } shouldBe mapOf(0 to it0, 1 to it1)
      }
    }
  }
}

/**
 * @return k s.t. forall beta: Blaire. beta(0..k) = alpha(0..k) => f(beta) = f(alpha)
 */
private suspend fun mu(f: suspend (Baire) -> Int, alpha: Baire): Int = runState(0) {
  f { n ->
    modify { max(it, n) }
    alpha(n)
  }
  get()
}

private suspend fun findNeighborhood(predicate: suspend (Cantor) -> Boolean): Map<Int, Bit> =
  runReader(persistentHashMapOf<Int, Bit>().builder(), { build().builder() }) {
    handle {
      predicate { i ->
        ask()[i] ?: use { k ->
          val current = ask().build()
          k(One) || run {
            // reset state
            ask().clear()
            ask().putAll(current)
            k(Zero)
          }
        }.also { ask()[i] = it }
      }
    }
    ask().build()
  }

private suspend fun epsilon(predicate: suspend (Cantor) -> Boolean): Cantor {
  val neighborhood = findNeighborhood(predicate)
  return { i -> neighborhood[i] ?: One }
}

private suspend fun exists(predicate: suspend (Cantor) -> Boolean): Boolean = predicate(epsilon(predicate))

private suspend fun forAll(predicate: suspend (Cantor) -> Boolean): Boolean = !exists { !predicate(it) }

private suspend infix fun <T> (suspend (Cantor) -> T).cantorEq(other: suspend (Cantor) -> T): Boolean =
  forAll { a -> this(a) == other(a) }

private suspend fun modulus(f: suspend (Cantor) -> Int): Int = least { n ->
  forAll { a ->
    forAll { b ->
      eq(n, a, b) implies (f(a) == f(b))
    }
  }
}

private inline fun least(p: (Int) -> Boolean): Int {
  var n = 0
  while (true) {
    if (p(n)) return n
    n++
  }
}

private suspend fun eq(n: Int, a: Cantor, b: Cantor): Boolean {
  for (i in 0 until n) {
    if (a(i) != b(i)) return false
  }
  return true
}

private infix fun Boolean.implies(other: Boolean): Boolean = !this || other

private suspend infix fun Cantor.project(k: Int) = this(k).ordinal