import io.kotest.common.Platform
import io.kotest.common.platform
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

suspend fun <T> Prompt<List<T>>.yield(x: T) = shift { k -> listOf(x) + k(Unit) }

suspend fun <T> Prompt<List<T>>.yieldAll(xs: List<T>) = shift { k -> xs + k(Unit) }

class ContinuationTest {
  suspend fun Prompt<Int>.foo(): Int = shift { k -> k(k(k(7))) } + 1

  suspend fun Prompt<Int>.bar(): Int = 2 * foo()

  @Test
  fun noContinuation() = runTest {
    topReset<Int> {
      42
    } shouldBe 42
  }

  @Test
  fun simpleContinuations() = runTest {
    // Examples from https://en.wikipedia.org/wiki/Delimited_continuation
    topReset<Int> {
      val value = shift { k -> k(5) }
      shift { k -> k(value + 1) }
    } * 2 shouldBe 12
    topReset {
      shift { k -> k(k(4)) } * 2
    } + 1 shouldBe 17
    topReset<List<Int>> {
      yield(1)
      yield(2)
      yield(3)
      emptyList()
    } shouldBe listOf(1, 2, 3)
    // Example from https://www.scala-lang.org/old/node/2096
    topReset {
      bar()
    } shouldBe 70
  }

  @Test
  fun controlVsShift() = runTest {
    var shiftPostActCount = 0
    var shiftDiscardCount = 0
    val shiftResult = topReset<Int> {
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
    val controlResult = topReset<Int> {
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
  fun nestedContinuations() = runTest {
    topReset<List<Int>> {
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
  fun await() = runTest {
    val mark = testTimeSource.markNow()
    val a = async {
      delay(500.milliseconds)
      5
    }
    val result = topReset {
      delay(200.milliseconds)
      val value = a.await()
      delay(100.milliseconds)
      value + 1
    }
    mark.elapsedNow() shouldBe 600.milliseconds
    result shouldBe 6
  }

  @Test
  fun stackSafety() = runTest {
    val n = stackSafeIterations
    topReset<Int> {
      repeat(n) {
        shift { k -> k(Unit) + it }
      }
      0
    } shouldBe n * (n - 1) / 2
  }

  @Test
  fun manyIterations() = runTest {
    val n = 100_000
    val result = topReset<Int> {
      shift { k ->
        (1..n).sumOf { k(it) }
      }
      1
    }
    result shouldBe n
  }

  @Test
  fun shiftTests() = runTest {
    10 + topReset<Int> {
      2 + shift<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    10 * topReset<Int> {
      shift {
        5 * shift<Int, _> { f -> f(1) + 1 }
      }
    } shouldBe 60
    run {
      suspend fun <R> Prompt<R>.f(x: R) = shift<R, R> { k -> k(k(x)) }
      1 + topReset<Int> { 10 + f(100) }
    } shouldBe 121
    run {
      suspend fun Prompt<String>.x() = shift { f -> "a" + f("") }
      topReset<String> {
        shift { x() }
      }
    } shouldBe "a"
    topReset<String> {
      shift { g ->
        shift { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    topReset<String> {
      shift { g ->
        shift { f -> "a" + f("") }
        g("b")
      }
    } shouldBe "ab"
  }

  @Test
  fun controlTests() = runTest {
    10 + topReset<Int> {
      2 + control<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    // (prompt (control g (control f (cons 'a (f '())))))
    topReset<String> {
      control { control { f -> "a" + f("") } }
    } shouldBe "a"
    // (prompt (let ((x (control f (cons 'a (f '()))))) (control g x)))
    topReset<String> {
      val x = control { f -> "a" + f("") }
      control { x }
    } shouldBe ""
    // (prompt (let ((x (control f (cons 'a (f '()))))) (control g (g x))))
    topReset<String> {
      val x = control { f -> "a" + f("") }
      control { g -> g(x) }
    } shouldBe "a"
    topReset<Int> {
      control { l ->
        1 + l(0)
      }
      control { 2 }
    } shouldBe 2
    topReset<String> {
      control { f -> "a" + f("") }
    } shouldBe "a"
    topReset<String> {
      control { g ->
        control { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    topReset<String> {
      control { f -> "a" + f("") }
      control { g -> "b" }
    } shouldBe "b"
    topReset<String> {
      control { f -> "a" + f("") }
      control { g -> g("b") }
    } shouldBe "ab"
    // (prompt (control g (let ((x (control f (cons 'a (f '()))))) (cons 'b '()))))
    topReset<String> {
      control { g ->
        control { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    topReset<String> {
      control { g ->
        "b" + control { f -> "a" + f("") }
      }
    } shouldBe "ab"
  }

  @Test
  fun shift0Tests() = runTest {
    10 + topReset<Int> {
      2 + shift0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    topReset<String> {
      "a" + reset<String> {
        shift0 { _ -> shift0 { _ -> "" } }
      }
    } shouldBe ""
    topReset<String> {
      "a" + reset<String> {
        reset<String> {
          shift0 { _ -> shift0 { _ -> "" } }
        }
      }
    } shouldBe "a"
  }

  @Test
  fun abort0Tests() = runTest {
    10 + topReset<Int> {
      2 + shift0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    topReset<String> {
      "a" + reset<String> {
        abort0 { abort0 { "" } }
      }
    } shouldBe ""
    topReset<String> {
      "a" + reset<String> {
        reset<String> {
          abort0 { abort0 { "" } }
        }
      }
    } shouldBe "a"
  }

  @Test
  fun control0Tests() = runTest {
    10 + topReset<Int> {
      2 + control0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    topReset<String> {
      reset {
        val x = control0 { f -> "a" + f("") }
        control0 { x }
      }
    } shouldBe ""
    topReset<String> {
      "a" + reset<String> {
        control0 { _ -> control0 { _ -> "" } }
      }
    } shouldBe ""
  }

  @Test
  fun lazyShifts() = runTest {
    topReset<Int> {
      val x = reset {
        shift { k -> k(1) + k(1) + 1 }
      }
      x + 1
    } shouldBe 4
  }
}

val stackSafeIterations: Int = when (platform) {
  Platform.JVM -> 20_000
  else -> 1000
}