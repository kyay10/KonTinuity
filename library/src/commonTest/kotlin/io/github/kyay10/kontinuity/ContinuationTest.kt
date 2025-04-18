package io.github.kyay10.kontinuity

import io.kotest.common.Platform
import io.kotest.common.platform
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest as coroutinesRunTest

suspend fun <T> Prompt<List<T>>.yield(x: T) = shift { k -> listOf(x) + k(Unit) }

suspend fun <T> Prompt<List<T>>.yieldAll(xs: List<T>) = shift { k -> xs + k(Unit) }

class ContinuationTest {
  suspend fun Prompt<Int>.foo(): Int = shift { k -> k(k(k(7))) } + 1

  suspend fun Prompt<Int>.bar(): Int = 2 * foo()

  @Test
  fun noContinuation() = runTestCC {
    newReset {
      42
    } shouldBe 42
  }

  @Test
  fun simpleContinuations() = runTestCC {
    // Examples from https://en.wikipedia.org/wiki/Delimited_continuation
    newReset {
      val value = shift { k -> k(5) }
      shift { k -> k(value + 1) }
    } * 2 shouldBe 12
    newReset {
      shift { k -> k(k(4)) } * 2
    } + 1 shouldBe 17
    newReset {
      yield(1)
      yield(2)
      yield(3)
      emptyList()
    } shouldBe listOf(1, 2, 3)
    // Example from https://www.scala-lang.org/old/node/2096
    newReset {
      bar()
    } shouldBe 70
  }

  @Test
  fun controlVsShift() = runTestCC {
    var shiftPostActCount = 0
    var shiftDiscardCount = 0
    val shiftResult = newReset {
      val act = shift<suspend () -> Unit, _> { k ->
        k { }
        k {
          shift { _ ->
            shiftDiscardCount++ // Capturing and discarding the continuation...
            42
          }
        }
        k { }
      }
      act()
      shiftPostActCount++ // Doing Stuff
      0
    }
    shiftResult shouldBe 0
    shiftPostActCount shouldBe 2
    shiftDiscardCount shouldBe 1
    var controlPostActCount = 0
    var controlDiscardCount = 0
    val controlResult = newReset {
      val act = control<suspend () -> Unit, _> { k ->
        k { }
        k {
          control { _ ->
            controlDiscardCount++ // Capturing and discarding the continuation...
            42
          }
        }
        k { }
      }
      act()
      reset {
        controlPostActCount++ // Doing Stuff
      }
      0
    }
    controlResult shouldBe 42
    controlPostActCount shouldBe 1
    controlDiscardCount shouldBe 1
  }

  @Test
  fun nestedContinuations() = runTestCC {
    newReset {
      yield(1)
      yieldAll(reset {
        yield(4)
        yield(5)
        yield(6)
        emptyList()
      })
      yield(2)
      yield(3)
      emptyList()
    } shouldBe listOf(1, 4, 5, 6, 2, 3)
  }

  @OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
  @Test
  fun await() = coroutinesRunTest {
    val mark = testTimeSource.markNow()
    val a = async {
      delay(500.milliseconds)
      5
    }
    val result = runCC {
      newReset {
        delay(200.milliseconds)
        val value = a.await()
        delay(100.milliseconds)
        value + 1
      }
    }
    mark.elapsedNow() shouldBe 600.milliseconds
    result shouldBe 6
  }

  @Test
  fun stackSafety() = runTestCC {
    val n = stackSafeIterations
    newReset<Int> {
      repeat(n) {
        shiftOnce { k -> k(Unit) + it }
      }
      0
    } shouldBe n * (n - 1) / 2
  }

  @Test
  fun manyIterations() = runTestCC {
    val n = 100_000
    val result = newReset<Int> {
      shift { k ->
        (1..n).sumOf { k(it) }
      }
      1
    }
    result shouldBe n
  }

  @Test
  fun shiftTests() = runTestCC {
    10 + newReset<Int> {
      2 + shift<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    10 * newReset<Int> {
      shift {
        5 * shift<Int, _> { f -> f(1) + 1 }
      }
    } shouldBe 60
    run {
      suspend fun <R> Prompt<R>.f(x: R) = shift { k -> k(k(x)) }
      1 + newReset<Int> { 10 + f(100) }
    } shouldBe 121
    run {
      suspend fun Prompt<String>.x() = shift { f -> "a" + f("") }
      newReset {
        shift { x() }
      }
    } shouldBe "a"
    newReset {
      shift { g ->
        shift { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    newReset {
      shift { g ->
        shift { f -> "a" + f("") }
        g("b")
      }
    } shouldBe "ab"
  }

  @Test
  fun controlTests() = runTestCC {
    10 + newReset<Int> {
      2 + control<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    // (prompt (control g (control f (cons 'a (f '())))))
    newReset {
      control { control { f -> "a" + f("") } }
    } shouldBe "a"
    // (prompt (let ((x (control f (cons 'a (f '()))))) (control g x)))
    newReset {
      val x = control { f -> "a" + f("") }
      control { x }
    } shouldBe ""
    // (prompt (let ((x (control f (cons 'a (f '()))))) (control g (g x))))
    newReset {
      val x = control { f -> "a" + f("") }
      control { g -> g(x) }
    } shouldBe "a"
    newReset<Int> {
      control { l ->
        1 + l(0)
      }
      control { 2 }
    } shouldBe 2
    newReset {
      control { f -> "a" + f("") }
    } shouldBe "a"
    newReset {
      control { g ->
        control { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    newReset {
      control { f -> "a" + f("") }
      control { g -> "b" }
    } shouldBe "b"
    newReset {
      control { f -> "a" + f("") }
      control { g -> g("b") }
    } shouldBe "ab"
    // (prompt (control g (let ((x (control f (cons 'a (f '()))))) (cons 'b '()))))
    newReset {
      control { g ->
        control { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    newReset {
      control { g ->
        "b" + control { f -> "a" + f("") }
      }
    } shouldBe "ab"
  }

  @Test
  fun shift0Tests() = runTestCC {
    10 + newReset<Int> {
      2 + shift0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    newReset {
      "a" + reset {
        shift0 { _ -> shift0 { _ -> "" } }
      }
    } shouldBe ""
    newReset {
      "a" + reset {
        reset {
          shift0 { _ -> shift0 { _ -> "" } }
        }
      }
    } shouldBe "a"
  }

  @Test
  fun abort0Tests() = runTestCC {
    10 + newReset<Int> {
      2 + shift0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    newReset{
      "a" + reset {
        abortS0 { abort0("") }
      }
    } shouldBe ""
    newReset {
      "a" + reset {
        reset {
          abortS0 { abort0("") }
        }
      }
    } shouldBe "a"
  }

  @Test
  fun control0Tests() = runTestCC {
    10 + newReset<Int> {
      2 + control0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    newReset {
      reset {
        val x = control0 { f -> "a" + f("") }
        control0 { x }
      }
    } shouldBe ""
    newReset {
      "a" + reset {
        control0 { _ -> control0 { _ -> "" } }
      }
    } shouldBe ""
  }

  @Test
  fun lazyShifts() = runTestCC {
    newReset {
      val x = reset {
        shift { k -> k(1) + k(1) + 1 }
      }
      x + 1
    } shouldBe 4
  }

  @Test
  fun handlingContext() = runTest {
    runCC {
      runReader(1) {
        newReset {
          pushReader(2) {
            ask() shouldBe 2
            shift { k -> k(ask()) } shouldBe 1
            shift0 { k -> k(ask()) } shouldBe 1
            ask() shouldBe 2
            inHandlingContext { ask() } shouldBe 1
            inHandlingContext(deleteDelimiter = true) { ask() } shouldBe 1
            ask() shouldBe 2
          }
        }
      }
    }
    runCC {
      runReader(1) {
        newReset {
          pushReader(2) {
            ask() shouldBe 2
            shift { k ->
              val v = ask()
              pushReader(3) { k(v) }
            } shouldBe 1
            ask() shouldBe 2
            shift0 { k -> k(ask()) } shouldBe 3
            shift0 { k -> shift0 { k(ask()) } } shouldBe 1
            ask() shouldBe 2
            shift { k ->
              val v = ask()
              pushReader(4) { k(v) }
            } shouldBe 1
            ask() shouldBe 2
            // Introduces an extra delimiter
            shift { k -> k(ask()) } shouldBe 4
            ask() shouldBe 2
            inHandlingContext { ask() } shouldBe 4
            inHandlingContext { inHandlingContext { ask() } } shouldBe 4
            inHandlingContext { inHandlingContext { inHandlingContext { ask() } } } shouldBe 1
            inHandlingContext(deleteDelimiter = false) { inHandlingContext { inHandlingContext { ask() } } } shouldBe 4
            inHandlingContext(deleteDelimiter = false) { inHandlingContext { inHandlingContext { inHandlingContext { ask() } } } } shouldBe 1
            ask() shouldBe 2
          }
        }
      }
    }
  }

  @Test
  fun ex4dot2dot1() = runTestCC {
    newReset {
      shift0 { k -> k(k(100)) } + 10
    } + 1 shouldBe 121
  }

  @Test
  fun ex4dot2dot2() = runTestCC {
    newReset p1@{
      if (newReset p2@{
          shift<Boolean, Int> { 21 }
        }) 1 else 2
    } * 2 shouldBe 42
  }
}

val stackSafeIterations: Int = when (platform) {
  Platform.JVM -> 20_000
  else -> 1000
}