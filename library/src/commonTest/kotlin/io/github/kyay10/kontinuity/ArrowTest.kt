package io.github.kyay10.kontinuity

import arrow.core.Either.Left
import io.kotest.matchers.shouldBe
import kotlin.test.Test

// From https://github.com/arrow-kt/arrow-core/pull/226
class ArrowTest {
  @Test
  fun yieldAListAndStackSafety() = runTestCC {
    newReset {
      suspend fun <A> Prompt<List<A>>.yield(a: A): Unit = shiftOnce { k -> listOf(a) + k(Unit) }
      for (i in 0..10_000) yield(i)
      emptyList()
    } shouldBe (0..10_000).toList()
  }

  @Test
  fun shortCircuit() = runTestCC {
    newReset {
      shiftOnce { Left("No thank you") }
    } shouldBe Left("No thank you")
  }

  @Test
  fun resetTest() = runTestCC {
    newReset {
      shiftOnce { it(1) }
    } shouldBe 1
  }

  // This also comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows that shift surrounds the
  //  captured continuation and the function receiving it with reset.
  @Test
  fun shiftAndControlDistinction() = runTestCC {
    newReset {
      shiftOnce { it("") }
      shiftOnce { f -> "a" + f("") }
    } shouldBe "a"
  }

  @Test
  fun nestedResetCallingBetweenScopes() = runTestCC {
    newReset {
      val a: Int = shiftOnce { it(5) }
      a + newReset<Int> fst@{
        val i: Int = shiftOnce { it(10) }
        newReset snd@{
          val j: Int = shiftOnce { it(20) }
          val k: Int = this@fst.shiftOnce { it(30) }
          i + j + k
        }
      } shouldBe 65
    }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopes() = runTestCC {
    newReset fst@{
      val a: Int = shiftOnce { it(5) }
      a + newReset<Int> snd@{
        val i: Int = shiftOnce { it(10) }
        newReset third@{
          val j: Int = shiftOnce { it(20) }
          val k: Int = this@fst.shiftOnce { it(30) } + this@snd.shiftOnce<Int, _> { it(40) }
          newReset fourth@{
            val p: Int = shiftOnce { it(20) }
            val k2: Int = this@fst.shiftOnce { it(30) } + this@snd.shiftOnce<Int, _> { it(40) }
            val t: Int = this@third.shiftOnce { it(5) }
            i + j + k + p + k2 + t
          }
        }
      } shouldBe 200
    }
  }

  @Test
  fun nestedResetCallingBetweenScopesWithShortCircuit() = runTestCC {
    newReset {
      val a: Int = shiftOnce { it(5) }
      a + newReset<Int> fst@{
        val i: Int = shiftOnce { it(10) }
        newReset snd@{
          val j: Int = shiftOnce { it(20) }
          val k: Int = this@fst.shiftOnce { 5 }
          i + j + k
        }
      } shouldBe 10
    }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopesAndShortCircuit() = runTestCC {
    newReset fst@{
      val a: Int = shiftOnce { it(5) }
      a + newReset<Int> snd@{
        val i: Int = shiftOnce { it(10) }
        newReset third@{
          val j: Int = shiftOnce { it(20) }
          val k: Int = this@fst.shiftOnce { it(30) } + this@snd.shiftOnce<Int, _> { it(40) }
          newReset fourth@{
            val p: Int = shiftOnce { it(20) }
            val k2: Int = this@fst.shiftOnce { it(30) } + this@snd.shiftOnce<Int, _> { it(40) }
            val t: Int = this@third.shiftOnce { 5 }
            i + j + k + p + k2 + t
          }
        }
      } shouldBe 10
    }
  }
}