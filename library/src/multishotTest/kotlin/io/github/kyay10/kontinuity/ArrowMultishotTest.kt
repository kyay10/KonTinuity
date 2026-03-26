package io.github.kyay10.kontinuity

import kotlin.test.Test

class ArrowMultishotTest {
  @Test
  fun supportsMultishot() = runTestCC {
    handle<Int> {
      use { it(1) + it(2) } + 1
    } shouldEq 5
  }

  // This comes from http://homes.sice.indiana.edu/ccshan/recur/recur.pdf and shows how reset/shift should behave
  @Test
  fun multishotResetShift() = runTestCC {
    "a" + handle {
      "b" + use { f -> "1" + f(f("c")) }
    } shouldEq "a1bbc"
    "a" + handle {
      "b" + use { f -> "1" + f(f("c")) }
    } shouldEq "a1bbc"
  }

  @Test
  fun multshotNondet() = runTestCC {
    handle<List<Pair<Int, Int>>> {
      val i: Int = use { k -> k(10) + k(20) }
      val j: Int = use { k -> k(15) + k(25) }
      listOf(i to j)
    } shouldEq listOf(10 to 15, 10 to 25, 20 to 15, 20 to 25)
  }

  @Test
  fun multishotMoreThanTwice() = runTestCC {
    handle<List<Pair<Pair<Int, Int>, Int>>> {
      val i: Int = use { k -> k(10) + k(20) }
      val j: Int = use { k -> k(15) + k(25) }
      val k: Int = use { k -> k(17) + k(27) }
      listOf(i to j to k)
    } shouldEq listOf(10, 20).flatMap { i ->
      listOf(15, 25).flatMap { j ->
        listOf(17, 27).map { k ->
          i to j to k
        }
      }
    }
  }

  @Test
  fun multishotMoreThanTwiceAndWithMoreMultishotInvocations() = runTestCC {
    handle<List<Pair<Pair<Int, Int>, Int>>> {
      val i: Int = use { k -> k(10) + k(20) + k(30) + k(40) + k(50) }
      val j: Int = use { k -> k(15) + k(25) + k(35) + k(45) + k(55) }
      val k: Int = use { k -> k(17) + k(27) + k(37) + k(47) + k(57) }
      listOf(i to j to k)
    } shouldEq listOf(10, 20, 30, 40, 50).flatMap { i ->
      listOf(15, 25, 35, 45, 55).flatMap { j ->
        listOf(17, 27, 37, 47, 57).map { k -> i to j to k }
      }
    }
  }

  @Test
  fun multishotIsStacksafeRegardlessOfStackSize() = runTestCC {
    handle<Int> {
      // bring 10k elements on the stack
      var sum = 0
      for (i0 in 1..10_000) sum += useOnce<Int, _> { it(i0) }

      // run the continuation from here 10k times and sum the results
      // This is about as bad as a scenario as it gets :)
      val j = use {
        var sum2 = 0
        for (i0 in 1..10_000) sum2 += it(i0)
        sum2
      }

      sum + j
    } shouldEq 1883798664
  }
}