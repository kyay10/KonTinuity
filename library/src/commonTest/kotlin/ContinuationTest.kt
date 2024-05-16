import androidx.compose.runtime.Composable
import arrow.fx.coroutines.resourceScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

@Composable
fun <T> Reset<List<T>>.yield(x: T) = shift { k -> listOf(x) + k(null) }

@Composable
fun <T> Reset<List<T>>.yieldAll(xs: List<T>) = shift { k -> xs + k(null) }

class ContinuationTest {
  @Composable
  fun Reset<Int>.foo(): Int = shift { k -> k(k(k(7))) } + 1

  @Composable
  fun Reset<Int>.bar(): Int = 2 * foo()

  @Test
  fun noContinuation() = runTest {
    reset<Int> {
      42
    } shouldBe 42
  }

  @Test
  fun simpleContinuations() = runTest {
    // Examples from https://en.wikipedia.org/wiki/Delimited_continuation
    reset<Int> {
      val value = shift { k -> k(5) }
      shift { k -> k(value + 1) }
    } * 2 shouldBe 12
    reset {
      shift { k -> k(k(4)) } * 2
    } + 1 shouldBe 17
    reset<List<Int>> {
      yield(1)
      yield(2)
      yield(3)
      emptyList()
    } shouldBe listOf(1, 2, 3)
    // Example from https://www.scala-lang.org/old/node/2096
    reset {
      bar()
    } shouldBe 70
  }

  @Test
  fun controlVsShift() = runTest {
    var shiftPostActCount = 0
    var shiftDiscardCount = 0
    val shiftResult = reset<Int> {
      val act = shift<@Composable () -> Nothing?, _> { k ->
        k { null }
        k {
          shift { _ ->
            shiftDiscardCount++ // Capturing and discarding the continuation...
            42
          }
        }
        k { null }
      }
      act()
      effect {
        shiftPostActCount++ // Doing Stuff
      }
      0
    }
    shiftResult shouldBe 0
    shiftPostActCount shouldBe 2
    shiftDiscardCount shouldBe 1
    var controlPostActCount = 0
    var controlDiscardCount = 0
    val controlResult = reset<Int> {
      val act = control<@Composable () -> Nothing?, _> { k ->
        k { null }
        k {
          control { _ ->
            controlDiscardCount++ // Capturing and discarding the continuation...
            42
          }
        }
        k { null }
      }
      act()
      effect {
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
    reset<List<Int>> {
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
    val result = reset {
      val value = await {
        delay(200.milliseconds)
        a.await()
      }
      await { delay(100.milliseconds) }
      await { value + 1 }
    }
    mark.elapsedNow() shouldBe 600.milliseconds
    result shouldBe 6
  }

  @Test
  fun stackSafety() = runTest {
    resourceScope {
      val n = 10
      val result = reset<Int> {
        for (i in 0 until n) {
          shift { k -> (k(null) + i) }
        }
        0
      }
      result shouldBe n * (n - 1) / 2
    }
  }

  @Test
  fun manyIterations() = runTest {
    val n = 100
    // Quadratic
    val result = reset<Int> {
      shift { k ->
        (1..n).sumOf { k(it) }
      }
      1
    }
    result shouldBe n
  }

  @Test
  fun shiftTests() = runTest {
    10 + reset<Int> {
      2 + shift<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    10 * reset<Int> {
      shift {
        5 * shift<Int, _> { f -> f(1) + 1 }
      }
    } shouldBe 60
    run {
      @Composable
      fun <R> Reset<R>.f(x: R) = shift<R, R> { k -> k(k(x)) }
      1 + reset<Int> { 10 + f(100) }
    } shouldBe 121
    run {
      @Composable
      fun Reset<String>.x() = shift { f -> "a" + f("") }
      reset<String> {
        shift { x() }
      }
    } shouldBe "a"
    reset<String> {
      shift { g ->
        shift { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    reset<String> {
      shift { g ->
        shift { f -> "a" + f("") }
        g("b")
      }
    } shouldBe "ab"
  }

  @Test
  fun controlTests() = runTest {
    10 + reset<Int> {
      2 + control<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    // (prompt (control g (control f (cons 'a (f '())))))
    reset<String> {
      control { control { f -> "a" + f("") } }
    } shouldBe "a"
    // (prompt (let ((x (control f (cons 'a (f '()))))) (control g x)))
    reset<String> {
      val x = control { f -> "a" + f("") }
      control { x }
    } shouldBe ""
    // (prompt (let ((x (control f (cons 'a (f '()))))) (control g (g x))))
    reset<String> {
      val x = control { f -> "a" + f("") }
      control { g -> g(x) }
    } shouldBe "a"
    reset<Int> {
      control { l ->
        1 + l(0)
      }
      control { 2 }
    } shouldBe 2
    reset<String> {
      control { f -> "a" + f("") }
    } shouldBe "a"
    reset<String> {
      control { g ->
        control { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    reset<String> {
      control { f -> "a" + f("") }
      control { g -> "b" }
    } shouldBe "b"
    reset<String> {
      control { f -> "a" + f("") }
      control { g -> g("b") }
    } shouldBe "ab"
    // (prompt (control g (let ((x (control f (cons 'a (f '()))))) (cons 'b '()))))
    reset<String> {
      control { g ->
        control { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    reset<String> {
      control { g ->
        "b" + control { f -> "a" + f("") }
      }
    } shouldBe "ab"
  }

  @Test
  fun shift0Tests() = runTest {
    10 + reset0<Int> {
      2 + shift0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    reset0<String> {
      "a" + reset0<String> {
        shift0 { _ -> shift0 { _ -> "" } }
      }
    } shouldBe ""
    reset0<String> {
      "a" + reset0<String> {
        reset0<String> {
          shift0 { _ -> shift0 { _ -> "" } }
        }
      }
    } shouldBe "a"
  }

  @Test
  fun control0Tests() = runTest {
    10 + reset0<Int> {
      2 + control0<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    reset0<String> {
      reset0 {
        val x = control0 { f -> "a" + f("") }
        control0 { x }
      }
    } shouldBe ""
  }


  @Test
  fun lazyShifts() = runTest {
    reset<Int> {
      val x = reset {
        shift { k -> k(1) + k(1) + 1 }
      }
      x + 1
    } shouldBe 4
  }
}