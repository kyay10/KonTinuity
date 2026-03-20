package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ArrowMultishotTest {
  @Test
  fun supportsMultishot() = runTestCC {
    newReset<Int> {
      shift { it(1) + it(2) } + 1
    } shouldBe 5
  }

  // This comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows how reset/shift should behave
  @Test
  fun multishotResetShift() = runTestCC {
    "a" + newReset {
      "b" + shift { f -> "1" + f(f("c")) }
    } shouldBe "a1bbc"
    "a" + newReset {
      "b" + shift { f -> "1" + f(f("c")) }
    } shouldBe "a1bbc"
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
      val j = shift {
        var sum2 = 0
        for (i0 in 1..10_000) sum2 += it(i0)
        sum2
      }

      sum + j
    }
  }
}