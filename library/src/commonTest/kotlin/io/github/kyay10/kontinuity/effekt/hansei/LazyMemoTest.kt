package io.github.kyay10.kontinuity.effekt.hansei

import arrow.core.None
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LazyMemoTest {
  // NOTE: OCaml eval order is rtl! So our examples are "backwards"
  @Test
  fun baseline() = runTestCC {
    var expensiveComputation = 0
    fun <A> expensiveFunction(x: A): A = x.also { expensiveComputation++ }
    exactReify {
      val u = listOf(0, 1).uniformly()
      val x = expensiveFunction(listOf(u + 10, u + 20).uniformly())
      if (u == 0) None else Some(Triple(flip(0.5), x, x))
    } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(Some(Triple(true, 21, 21)))),
      Probable(0.125, Value.Leaf(Some(Triple(false, 21, 21)))),
      Probable(0.125, Value.Leaf(Some(Triple(true, 11, 11)))),
      Probable(0.125, Value.Leaf(Some(Triple(false, 11, 11)))),
      Probable(0.5, Value.Leaf(None)),
    )
    expensiveComputation shouldBe 4
  }

  @Test
  fun asyncAwait() = runTestCC {
    var expensiveComputation = 0
    fun <A> expensiveFunction(x: A): A = x.also { expensiveComputation++ }
    exactReify {
      val u = listOf(0, 1).uniformly()

      context(_: Probabilistic, _: MultishotScope)
      suspend fun x() = expensiveFunction(listOf(u + 10, u + 20).uniformly())

      if (u == 0) None else Some(Triple(flip(0.5), x(), x()))
    } shouldContainExactlyInAnyOrder listOf(
      Probable(0.0625, Value.Leaf(Some(Triple(true, 21, 21)))),
      Probable(0.0625, Value.Leaf(Some(Triple(false, 21, 21)))),
      Probable(0.0625, Value.Leaf(Some(Triple(true, 21, 11)))),
      Probable(0.0625, Value.Leaf(Some(Triple(false, 21, 11)))),
      Probable(0.0625, Value.Leaf(Some(Triple(true, 11, 21)))),
      Probable(0.0625, Value.Leaf(Some(Triple(false, 11, 21)))),
      Probable(0.0625, Value.Leaf(Some(Triple(true, 11, 11)))),
      Probable(0.0625, Value.Leaf(Some(Triple(false, 11, 11)))),
      Probable(0.5, Value.Leaf(None)),
    )
    expensiveComputation shouldBe 12
  }

  @Test
  fun stupidLazy() = runTestCC {
    var expensiveComputation = 0
    fun <A> expensiveFunction(x: A): A = x.also { expensiveComputation++ }
    exactReify {
      val u = listOf(0, 1).uniformly()
      val x = stupidLazy { expensiveFunction(listOf(u + 10, u + 20).uniformly()) }
      if (u == 0) None else Some(Triple(flip(0.5), x(), x()))
    } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(Some(Triple(true, 21, 21)))),
      Probable(0.25, Value.Leaf(Some(Triple(false, 21, 21)))),
      Probable(0.125, Value.Leaf(Some(Triple(true, 11, 11)))),
      Probable(0.5, Value.Leaf(None)),
    )
    expensiveComputation shouldBe 2
  }

  @Test
  fun letLazy() = runTestCC {
    var expensiveComputation = 0
    fun <A> expensiveFunction(x: A): A = x.also { expensiveComputation++ }
    exactReify {
      val u = listOf(0, 1).uniformly()
      val x = letLazy { expensiveFunction(listOf(u + 10, u + 20).uniformly()) }
      if (u == 0) None else Some(Triple(flip(0.5), x(), x()))
    } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(Some(Triple(true, 21, 21)))),
      Probable(0.125, Value.Leaf(Some(Triple(false, 21, 21)))),
      Probable(0.125, Value.Leaf(Some(Triple(true, 11, 11)))),
      Probable(0.125, Value.Leaf(Some(Triple(false, 11, 11)))),
      Probable(0.5, Value.Leaf(None)),
    )
    // Still called 4 times because `flip` comes first
    expensiveComputation shouldBe 4
  }

  @Test
  fun testl() = runTestCC {
    var expensiveComputation = 0
    fun <A> expensiveFunction(x: A): A = x.also { expensiveComputation++ }

    context(_: Probabilistic, _: MultishotScope)
    suspend fun testl1(): Pair<Int, Int> {
      val u = (1..2).uniformly()
      val x = expensiveFunction(listOf(2 * u, 3 * u).uniformly())
      return if (flip()) u to u else x to x
    }
    exactReify { testl1() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(6 to 6)),
      Probable(0.125, Value.Leaf(4 to 4)),
      Probable(0.125, Value.Leaf(3 to 3)),
      Probable(0.375, Value.Leaf(2 to 2)),
      Probable(0.25, Value.Leaf(1 to 1)),
    )
    expensiveComputation shouldBe 4
    context(_: Probabilistic, _: Memory, _: MultishotScope)
    suspend fun testl2(): Pair<Int, Int> {
      val u = (1..2).uniformly()
      val x = letLazy { expensiveFunction(listOf(2 * u, 3 * u).uniformly()) }
      return if (flip()) u to u else x() to x()
    }
    expensiveComputation = 0
    exactReify { testl2() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(6 to 6)),
      Probable(0.125, Value.Leaf(4 to 4)),
      Probable(0.125, Value.Leaf(3 to 3)),
      Probable(0.375, Value.Leaf(2 to 2)),
      Probable(0.25, Value.Leaf(1 to 1)),
    )
    expensiveComputation shouldBe 4
    expensiveComputation = 0
    sampleRejection(random(1).selector(), 10) { testl1() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.59999999999999998, Value.Leaf(2 to 2)),
      Probable(0.40000000000000002, Value.Leaf(1 to 1)),
    )
    expensiveComputation shouldBe 10
    expensiveComputation = 0
    sampleRejection(random(1).selector(), 10) { testl2() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.1, Value.Leaf(4 to 4)),
      Probable(0.4, Value.Leaf(2 to 2)),
      Probable(0.5, Value.Leaf(1 to 1)),
    )
    expensiveComputation shouldBe 2
    context(_: Probabilistic, _: Memory, _: MultishotScope)
    suspend fun testl3(): Triple<Int, Boolean, Int> {
      val u = (1..2).uniformly()
      val x = expensiveFunction(listOf(10 * u, 100 * u).uniformly())
      return Triple(u, flip(0.5), x)
    }
    expensiveComputation = 0
    exactReify { testl3() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(Triple(2, true, 200))),
      Probable(0.125, Value.Leaf(Triple(2, true, 20))),
      Probable(0.125, Value.Leaf(Triple(2, false, 200))),
      Probable(0.125, Value.Leaf(Triple(2, false, 20))),
      Probable(0.125, Value.Leaf(Triple(1, true, 100))),
      Probable(0.125, Value.Leaf(Triple(1, true, 10))),
      Probable(0.125, Value.Leaf(Triple(1, false, 100))),
      Probable(0.125, Value.Leaf(Triple(1, false, 10))),
    )
    expensiveComputation shouldBe 4
    context(_: Probabilistic, _: Memory, _: MultishotScope)
    suspend fun testl4(): Triple<Int, Boolean, Int> {
      val u = (1..2).uniformly()
      val x = letLazy { expensiveFunction(listOf(10 * u, 100 * u).uniformly()) }
      return Triple(u, flip(0.5), x().also { x() })
    }
    expensiveComputation = 0
    exactReify { testl4() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(Triple(2, true, 200))),
      Probable(0.125, Value.Leaf(Triple(2, true, 20))),
      Probable(0.125, Value.Leaf(Triple(2, false, 200))),
      Probable(0.125, Value.Leaf(Triple(2, false, 20))),
      Probable(0.125, Value.Leaf(Triple(1, true, 100))),
      Probable(0.125, Value.Leaf(Triple(1, true, 10))),
      Probable(0.125, Value.Leaf(Triple(1, false, 100))),
      Probable(0.125, Value.Leaf(Triple(1, false, 10))),
    )
    expensiveComputation shouldBe 8
    expensiveComputation = 0
    sampleRejection(random(1).selector(), 10) { testl3() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.1, Value.Leaf(Triple(2, true, 200))),
      Probable(0.2, Value.Leaf(Triple(2, true, 20))),
      Probable(0.3, Value.Leaf(Triple(1, true, 100))),
      Probable(0.1, Value.Leaf(Triple(1, true, 10))),
      Probable(0.3, Value.Leaf(Triple(1, false, 10))),
    )
    expensiveComputation shouldBe 10
    expensiveComputation = 0
    sampleImportance(random(1).selector(), 10) { testl3() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(Triple(2, true, 200))),
      Probable(0.125, Value.Leaf(Triple(2, true, 20))),
      Probable(0.125, Value.Leaf(Triple(2, false, 200))),
      Probable(0.125, Value.Leaf(Triple(2, false, 20))),
      Probable(0.125, Value.Leaf(Triple(1, true, 100))),
      Probable(0.125, Value.Leaf(Triple(1, true, 10))),
      Probable(0.125, Value.Leaf(Triple(1, false, 100))),
      Probable(0.125, Value.Leaf(Triple(1, false, 10))),
    )
    expensiveComputation shouldBe 4
    expensiveComputation = 0
    sampleRejection(random(1).selector(), 10) { testl4() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.2, Value.Leaf(Triple(2, true, 20))),
      Probable(0.1, Value.Leaf(Triple(2, false, 20))),
      Probable(0.3, Value.Leaf(Triple(1, true, 100))),
      Probable(0.1, Value.Leaf(Triple(1, true, 10))),
      Probable(0.3, Value.Leaf(Triple(1, false, 10))),
    )
    expensiveComputation shouldBe 10
    expensiveComputation = 0
    sampleImportance(random(1).selector(), 10) { testl4() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(Triple(2, true, 200))),
      Probable(0.125, Value.Leaf(Triple(2, true, 20))),
      Probable(0.125, Value.Leaf(Triple(2, false, 200))),
      Probable(0.125, Value.Leaf(Triple(2, false, 20))),
      Probable(0.125, Value.Leaf(Triple(1, true, 100))),
      Probable(0.125, Value.Leaf(Triple(1, true, 10))),
      Probable(0.125, Value.Leaf(Triple(1, false, 100))),
      Probable(0.125, Value.Leaf(Triple(1, false, 10))),
    )
    expensiveComputation shouldBe 8

    context(_: Probabilistic, _: Memory, _: MultishotScope)
    suspend fun testl51(): Pair<Int, Int> {
      val u = letLazy { (1..2).uniformly() }
      val x = expensiveFunction(listOf(2 * u(), 3 * u()).uniformly())
      return if (flip()) u() to u() else x to x
    }
    expensiveComputation = 0
    exactReify { testl51() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(6 to 6)),
      Probable(0.125, Value.Leaf(4 to 4)),
      Probable(0.125, Value.Leaf(3 to 3)),
      Probable(0.375, Value.Leaf(2 to 2)),
      Probable(0.25, Value.Leaf(1 to 1)),
    )
    expensiveComputation shouldBe 4

    context(_: Probabilistic, _: Memory, _: MultishotScope)
    suspend fun testl52(): Pair<Int, Int> {
      val u = letLazy { (1..2).uniformly() }
      val x = letLazy { expensiveFunction(listOf(2 * u(), 3 * u()).uniformly()) }
      return if (flip()) u() to u() else x() to x()
    }
    expensiveComputation = 0
    exactReify { testl52() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.125, Value.Leaf(6 to 6)),
      Probable(0.125, Value.Leaf(4 to 4)),
      Probable(0.125, Value.Leaf(3 to 3)),
      Probable(0.375, Value.Leaf(2 to 2)),
      Probable(0.25, Value.Leaf(1 to 1)),
    )
    expensiveComputation shouldBe 4


    context(_: Probabilistic, _: Memory, _: MultishotScope)
    suspend fun testl54(): Triple<Boolean, Int, Int> {
      val u = letLazy { (1..2).uniformly() }
      val x = letLazy {
        val uRes = u()
        val newU: suspend context(MultishotScope) () -> Int = { uRes }
        expensiveFunction(listOf(newU, u).uniformly()())
      }
      val c = flip()
      // "backwards" because RTL
      return if(c) Triple(c, x().also { u() }, x().let { u() })
      else Triple(c, u().also { x() }, u().let { x() })
    }
    expensiveComputation = 0
    exactReify { testl54() } shouldContainExactlyInAnyOrder listOf(
      Probable(0.25, Value.Leaf(Triple(true, 2, 2))),
      Probable(0.25, Value.Leaf(Triple(true, 1, 1))),
      Probable(0.25, Value.Leaf(Triple(false, 2, 2))),
      Probable(0.25, Value.Leaf(Triple(false, 1, 1))),
    )
    expensiveComputation shouldBe 8
  }
}

fun <R> stupidLazy(block: suspend context(MultishotScope) () -> R): suspend context(MultishotScope) () -> R {
  var result: R? = null
  return {
    if (result == null) {
      result = block()
    }
    result!!
  }
}