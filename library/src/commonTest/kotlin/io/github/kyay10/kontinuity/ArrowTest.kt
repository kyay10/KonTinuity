package io.github.kyay10.kontinuity

import arrow.core.Either.Left
import kotlin.test.Test

// From https://github.com/arrow-kt/arrow-core/pull/226
class ArrowTest {
  @Test
  fun yieldAListAndStackSafety() = runTestCC {
    handle<List<Int>> {
      for (i in 0..10_000) useOnce { k -> listOf(i) + k(Unit) }
      emptyList()
    } shouldEq (0..10_000).toList()
  }

  @Test
  fun shortCircuit() = runTestCC {
    handle {
      useOnce { Left("No thank you") }
    } shouldEq Left("No thank you")
  }

  @Test
  fun resetTest() = runTestCC {
    handle {
      useOnce { it(1) }
    } shouldEq 1
  }

  // This also comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows that shift surrounds the
  //  captured continuation and the function receiving it with reset.
  @Test
  fun shiftAndControlDistinction() = runTestCC {
    handle {
      useOnce { it(Unit) }
      useOnce { f -> "a" + f("") }
    } shouldEq "a"
  }

  @Test
  fun nestedResetCallingBetweenScopes() = runTestCC {
    handle {
      val a: Int = useOnce { it(5) }
      a + handle<Int> fst@{
        val i: Int = useOnce { it(10) }
        handle snd@{
          val j: Int = useOnce { it(20) }
          val k: Int = this@fst.useOnce { it(30) }
          i + j + k
        }
      } shouldEq 65
    }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopes() = runTestCC {
    handle fst@{
      val a: Int = useOnce { it(5) }
      a + handle<Int> snd@{
        val i: Int = useOnce { it(10) }
        handle third@{
          val j: Int = useOnce { it(20) }
          val k: Int = this@fst.useOnce { it(30) } + this@snd.useOnce<Int, _> { it(40) }
          handle fourth@{
            val p: Int = useOnce { it(20) }
            val k2: Int = this@fst.useOnce { it(30) } + this@snd.useOnce<Int, _> { it(40) }
            val t: Int = this@third.useOnce { it(5) }
            i + j + k + p + k2 + t
          }
        }
      } shouldEq 200
    }
  }

  @Test
  fun nestedResetCallingBetweenScopesWithShortCircuit() = runTestCC {
    handle {
      val a: Int = useOnce { it(5) }
      a + handle<Int> fst@{
        val i: Int = useOnce { it(10) }
        handle snd@{
          val j: Int = useOnce { it(20) }
          val k: Int = this@fst.useOnce { 5 }
          i + j + k
        }
      } shouldEq 10
    }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopesAndShortCircuit() = runTestCC {
    handle fst@{
      val a: Int = useOnce { it(5) }
      a + handle<Int> snd@{
        val i: Int = useOnce { it(10) }
        handle third@{
          val j: Int = useOnce { it(20) }
          val k: Int = this@fst.useOnce { it(30) } + this@snd.useOnce<Int, _> { it(40) }
          handle fourth@{
            val p: Int = useOnce { it(20) }
            val k2: Int = this@fst.useOnce { it(30) } + this@snd.useOnce<Int, _> { it(40) }
            val t: Int = this@third.useOnce { 5 }
            i + j + k + p + k2 + t
          }
        }
      } shouldEq 10
    }
  }
}