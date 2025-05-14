package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class LogicTest {
  @Test
  fun reader() = runTestCC {
    runReader(0) {
      bagOfN {
        runReader(ask() + 5) {
          ask()
        }
      } shouldBe listOf(5)
    }
    runReader(0) {
      bagOfN {
        runReader(ask() + 5) {
          ask()
        } to ask()
      } shouldBe listOf(5 to 0)
    }

    runReader(0) {
      bagOfN {
        if (flip()) runReader(ask() + 5) {
          ask()
        } else if (flip()) raise()
        else runReader(ask() + 3) {
          ask()
        }
      } shouldBe listOf(5, 3)
    }
  }

  @Test
  fun infinites() = runTestCC {
    bagOfN(5) {
      nats()
    } shouldBe (0..4).toList()
    bagOfN(5) {
      runReader(0) { nats() }
    } shouldBe (0..4).toList()
  }


  @Test
  fun more() = runTestCC {
    bagOfN {
      choose(listOf("Hello", "world"))
    }.foldRightIteratorless("!", String::conc) shouldBe "Hello world !"
    bagOfN {
      choose(listOf<String>())
    }.foldRightIteratorless("!", String::conc) shouldBe "!"
    bagOfN {
      choose(listOf("Hello", "world"))
    }.foldRightIteratorless("!") { s, _ -> s } shouldBe "Hello"
    bagOfN {
      odds5Down()
    }.foldRightIteratorless(11, Int::plus) shouldBe 20
    bagOfN {
      raise()
    }.foldRightIteratorless(11, Int::plus) shouldBe 11
    onceOrNull {
      odds5Down()
    } shouldBe 5
    onceOrNull {
      raise()
    } shouldBe null
    bagOfN {
      odds5Down()
    } shouldBe listOf(5, 3, 1)
    bagOfN(2) {
      odds5Down()
    } shouldBe listOf(5, 3)
    bagOfN(2) {
      raise()
    } shouldBe listOf()
    withLogic {
      bagOfN {
        fairBind({ sample() }) { raise() }
      } shouldBe bagOfN { raise() }
      bagOfN {
        fairBind({ sample() }) { it + choose(listOf(100, 200, 300)) }
      } shouldBe bagOfN { choose(listOf(101, 102, 201, 103, 301, 202, 203, 302, 303)) }
      bagOfN {
        fairBind({ sample() }) {
          ensure(it % 2 == 1)
          it + choose(listOf(100, 200, 300))
        }
      } shouldBe bagOfN { choose(listOf(101, 103, 201, 203, 301, 303)) }
    }
  }

  @Test
  fun splitLaws() = runTestCC {
    withLogic {
      split { raise() } shouldBe null
      val (x, rest) = split { sample() }.shouldNotBeNull()
      x shouldBe 1
      rest?.let { it() }.shouldNotBeNull().value shouldBe 2
    }
  }

  @Test
  fun fairDisjunctionLaws() = runTestCC {
    withLogic {
      bagOfN(4) { odds() } shouldBe (1..7 step 2).toList()
      bagOfN(4) { oddsOrTwoUnfair() } shouldBe (1..7 step 2).toList()
      bagOfN(4) { oddsOrTwoFair() } shouldBe listOf(1, 2, 3, 5)
      onceOrNull { oddsOrTwo() } shouldBe 2
      assertNonTerminatingCC { bagOfN(2) { oddsOrTwo() } }
    }
  }

  @Test
  fun fairConjunctionLaws() = runTestCC {
    withLogic {
      bagOfN(4) {
        val x = fairBind({ if (flip()) 0 else 1 }) {
          odds() + it
        }
        ensure(x % 2 == 0)
        x
      } shouldBe (2..8 step 2).toList()
      bagOfN(4) {
        fairBind({
          fairBind({ if (flip()) 0 else 1 }) {
            odds() + it
          }
        }) {
          ensure(it % 2 == 0)
          it
        }
      } shouldBe (2..8 step 2).toList()
      assertNonTerminatingCC {
        bagOfN(4) {
          fairBind({
            if (flip()) 0 else 1
          }) { a ->
            fairBind({ odds() + a }) { x ->
              bridge { yield() }
              ensure(x % 2 == 0)
              x
            }
          }
        }
      }
      assertNonTerminatingCC {
        bagOfN(4) {
          fairBind({
            if (flip()) 0 else 1
          }) { a ->
            val x = odds() + a
            bridge { yield() }
            ensure(x % 2 == 0)
            x
          }
        }
      }
      assertNonTerminatingCC {
        bagOfN(4) {
          val a = if (flip()) 0 else 1
          val x = odds() + a
          bridge { yield() }
          ensure(x % 2 == 0)
          x
        }
      }
    }
  }

  @Test
  fun ifteSoftCut() = runTestCC {
    withLogic {
      bagOfN(10) {
        val n = odds()
        ensure(n > 1)
        val d = iota(n - 1)
        ensure(d > 1 && n % d == 0)
        n
      } shouldBe listOf(9, 15, 15, 21, 21, 25, 27, 27, 33, 33)
      bagOfN(10) {
        val n = odds()
        ensure(n > 1)
        val d = iota(n - 1)
        ensure(d > 1 && n % d != 0)
        n
      } shouldBe listOf(3, 5, 5, 5, 7, 7, 7, 7, 7, 9)
      bagOfN(10) {
        val n = odds()
        ensure(n > 1)
        gnot {
          val d = iota(n - 1)
          ensure(d > 1 && n % d == 0)
        }
        n
      } shouldBe listOf(3, 5, 7, 11, 13, 17, 19, 23, 29, 31)
    }
  }

  @Test
  fun oncePruning() = runTestCC {
    val input = listOf(5, 0, 3, 4, 0, 1)
    bagOfN { bogoSort(input) } shouldBe listOf(input.sorted(), input.sorted())
    bagOfN { once { bogoSort(input) } } shouldBe listOf(input.sorted())
  }

  @Test
  fun ensure() = runTestCC {
    bagOfN(5) {
      nats().also { ensure(it % 2 == 1) }
    } shouldBe (1..9 step 2).toList()
  }

  @Test
  fun readerInside() = runTestCC {
    withLogic {
      bagOfN(2) {
        runReader(0) { if(flip()) 0 else 1 }
      } shouldBe (0..1).toList()
    }
  }
}

context(_: Amb, _: Exc)
private suspend fun <T : Comparable<T>> MultishotScope.bogoSort(list: List<T>): List<T> = permute(list).also { ensure(it.isSorted()) }

context(_: Amb, _: Exc)
private suspend fun <T> MultishotScope.permute(list: List<T>): List<T> =
  list.foldRightIteratorless(persistentListOf()) { i, acc -> insert(acc, i) }

private fun <T : Comparable<T>> List<T>.isSorted(): Boolean = zipWithNext { s1, s2 -> s1 <= s2 }.all { it }

context(_: Amb, _: Exc)
suspend fun <T> MultishotScope.insert(list: List<T>, element: T): PersistentList<T> {
  val index = choose(0..list.size)
  return list.toPersistentList().add(index, element)
}

context(_: Amb, _: Exc)
private suspend fun MultishotScope.iota(n: Int) = choose(1..n)

private suspend fun <R> MultishotScope.assertNonTerminatingCC(block: suspend MultishotScope.() -> R) =
  bridge { nonTerminatingCC(block) } shouldBe null

private suspend fun <R> nonTerminatingCC(block: suspend MultishotScope.() -> R) =
  withContext(Dispatchers.Default.limitedParallelism(1)) {
    withTimeoutOrNull(10.milliseconds) { runCC(block) }
  }

context(_: Amb, _: Exc)
private suspend fun MultishotScope.sample() = choose(listOf(1, 2, 3))

private infix fun String.conc(other: String) = "$this $other"

private inline fun withLogic(block: context(Logic) () -> Unit) {
  for (logic in listOf(LogicDeep, LogicTree, LogicSimple)) {
    block(logic)
  }
}

context(_: Amb, _: Exc)
private suspend fun MultishotScope.nats(): Int = if (flip()) 0 else 1 + nats()

context(_: Amb, _: Exc)
private suspend fun MultishotScope.odds(): Int = if (flip()) 1 else 2 + odds()

context(_: Amb, _: Exc)
private suspend fun MultishotScope.oddsOrTwoUnfair(): Int = if (flip()) odds() else 2

context(_: Logic, _: Amb, _: Exc)
private suspend fun MultishotScope.oddsOrTwoFair(): Int = interleave({ odds() }, { 2 })

context(_: Logic, _: Amb, _: Exc)
private suspend fun MultishotScope.oddsOrTwo(): Int {
  val x = oddsOrTwoFair()
  bridge { yield() }
  ensure(x % 2 == 0)
  return once { x }
}

context(_: Amb, _: Exc)
private suspend fun MultishotScope.odds5Down(): Int = when {
  flip() -> 5
  flip() -> raise()
  flip() -> raise()
  flip() -> 3
  else -> 1
}