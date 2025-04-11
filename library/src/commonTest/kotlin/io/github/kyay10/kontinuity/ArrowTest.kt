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
      shift { Left("No thank you") }
    } shouldBe Left("No thank you")
  }

  @Test
  fun supportsMultishot() = runTestCC {
    newReset<Int> {
      shift { it(1) + it(2) } + 1
    } shouldBe 5
  }

  @Test
  fun resetTest() = runTestCC {
    newReset {
      reset {
        shift { it(1) }
      }
    } shouldBe 1
  }

  // This comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows how reset/shift should behave
  @Test
  fun multishotResetShift() = runTestCC {
    newReset<List<Char>> {
      listOf('a') + reset {
        listOf('b') + shift<List<Char>, _> { f -> listOf('1') + f(f(listOf('c'))) }
      }
    } shouldBe listOf('a', '1', 'b', 'b', 'c')
    newReset<List<Char>> {
      listOf('a') + reset {
        listOf('b') + shift<List<Char>, _> { f -> listOf('1') + f(f(listOf('c'))) }
      }
    } shouldBe listOf('a', '1', 'b', 'b', 'c')
  }

  // This also comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows that shift surrounds the
  //  captured continuation and the function receiving it with reset.
  @Test
  fun shiftAndControlDistinction() = runTestCC {
    newReset<String> {
      reset {
        suspend fun y() = shift<String, _> { f -> "a" + f("") }
        shift<String, _> { y() }
      }
    } shouldBe "a"
    // TODO this is not very accurate, probably not correct either
    newReset<String> {
      shift { it("") }
      shift { f -> "a" + f("") }
    } shouldBe "a"
  }

  @Test
  fun multshotNondet() = runTestCC {
    newReset<List<Pair<Int, Int>>> {
      val i: Int = shift { k -> k(10) + k(20) }
      val j: Int = shift { k -> k(15) + k(25) }
      listOf(i to j)
    } shouldBe listOf(10 to 15, 10 to 25, 20 to 15, 20 to 25)
  }

  @Test
  fun multishotMoreThanTwice() = runTestCC {
    newReset<List<Pair<Pair<Int, Int>, Int>>> {
      val i: Int = shift { k -> k(10) + k(20) }
      val j: Int = shift { k -> k(15) + k(25) }
      val k: Int = shift { k -> k(17) + k(27) }
      listOf(i to j to k)
    } shouldBe listOf(10, 20).flatMap { i ->
      listOf(15, 25).flatMap { j ->
        listOf(17, 27).map { k ->
          i to j to k
        }
      }
    }
  }

  @Test
  fun multishotMoreThanTwiceAndWithMoreMultishotInvocations() = runTestCC {
    newReset<List<Pair<Pair<Int, Int>, Int>>> {
      val i: Int = shift { k -> k(10) + k(20) + k(30) + k(40) + k(50) }
      val j: Int = shift { k -> k(15) + k(25) + k(35) + k(45) + k(55) }
      val k: Int = shift { k -> k(17) + k(27) + k(37) + k(47) + k(57) }
      listOf(i to j to k)
    } shouldBe listOf(10, 20, 30, 40, 50).flatMap { i ->
      listOf(15, 25, 35, 45, 55).flatMap { j ->
        listOf(17, 27, 37, 47, 57).map { k -> i to j to k }
      }
    }
  }

  @Test
  fun multishotIsStacksafeRegardlessOfStackSize() = runTestCC {
    newReset<Int> {
      // bring 10k elements on the stack
      var sum = 0
      for (i0 in 1..10_000) sum += shiftOnce<Int, _> { it(i0) }

      // run the continuation from here 10k times and sum the results
      // This is about as bad as a scenario as it gets :)
      val j: Int = shift {
        var sum2 = 0
        for (i0 in 1..10_000) sum2 += it(i0)
        sum2
      }

      sum + j
    }
  }

  @Test
  fun nestedResetCallingBetweenScopes() = runTestCC {
    newReset {
      val a: Int = shift { it(5) }
      a + newReset<Int> fst@{
        val i: Int = shift { it(10) }
        newReset snd@{
          val j: Int = shift { it(20) }
          val k: Int = this@fst.shift { it(30) }
          i + j + k
        }
      } shouldBe 65
    }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopes() = runTestCC {
    newReset fst@{
      val a: Int = shift { it(5) }
      a + newReset<Int> snd@{
        val i: Int = shift { it(10) }
        newReset third@{
          val j: Int = shift { it(20) }
          val k: Int = this@fst.shift { it(30) } + this@snd.shift<Int, _> { it(40) }
          newReset fourth@{
            val p: Int = shift { it(20) }
            val k2: Int = this@fst.shift { it(30) } + this@snd.shift<Int, _> { it(40) }
            val t: Int = this@third.shift { it(5) }
            i + j + k + p + k2 + t
          }
        }
      } shouldBe 200
    }
  }

  @Test
  fun nestedResetCallingBetweenScopesWithShortCircuit() = runTestCC {
    newReset {
      val a: Int = shift { it(5) }
      a + newReset<Int> fst@{
        val i: Int = shift { it(10) }
        newReset snd@{
          val j: Int = shift { it(20) }
          val k: Int = this@fst.shift { 5 }
          i + j + k
        }
      } shouldBe 10
    }
  }

  @Test
  fun nestedResetCallingBetweenALotOfScopesAndShortCircuit() = runTestCC {
    newReset fst@{
      val a: Int = shift { it(5) }
      a + newReset<Int> snd@{
        val i: Int = shift { it(10) }
        newReset third@{
          val j: Int = shift { it(20) }
          val k: Int = this@fst.shift { it(30) } + this@snd.shift<Int, _> { it(40) }
          newReset fourth@{
            val p: Int = shift { it(20) }
            val k2: Int = this@fst.shift { it(30) } + this@snd.shift<Int, _> { it(40) }
            val t: Int = this@third.shift { 5 }
            i + j + k + p + k2 + t
          }
        }
      } shouldBe 10
    }
  }
}