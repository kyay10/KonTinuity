import _Reset.Companion._nestedReset
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
fun <T> _Reset<List<T>>.yield(x: T) =
  _shiftC { k -> listOf(x) + k(Unit) }

@Composable
fun <T> _Reset<List<T>>.yieldAll(xs: List<T>) =
  _shiftC { k -> xs + k(Unit) }

class BasicShiftTest {
  @Composable
  fun _Reset<Int>.foo(): Int = _shiftC { k -> k(k(k(7))) } + 1

  @Composable
  fun _Reset<Int>.bar(): Int = 2 * foo()

  @Test
  fun noContinuation() = runTest {
    _reset<Int> {
      42
    } shouldBe 42
  }

  @Test
  fun simpleContinuations() = runTest {
    // Examples from https://en.wikipedia.org/wiki/Delimited_continuation
    _reset<Int> {
      val value = _shiftC { k -> k(5) }
      _shiftC { k -> k(value + 1) }
    } * 2 shouldBe 12
    _reset {
      _shiftC { k -> k(k(4)) } * 2
    } + 1 shouldBe 17
    _reset<List<Int>> {
      yield(1)
      yield(2)
      yield(3)
      emptyList()
    } shouldBe listOf(1, 2, 3)
    // Example from https://www.scala-lang.org/old/node/2096
    _reset {
      bar()
    } shouldBe 70
  }

  @Test
  fun complicatedShifts() = runTest {
    var shiftPostActCount = 0
    var shiftDiscardCount = 0
    val shiftResult = _reset<Int> {
      val act = _shiftC<@Composable () -> Nothing?, _> { k ->
        k { null }
        k {
          _shiftC { _ ->
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
  }

  @Test
  fun nestedContinuations() = runTest {
    _reset<List<Int>> {
      yield(1)
      yieldAll(_nestedReset<List<Int>> {
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
    val result = _reset {
      val value = await {
        delay(200.milliseconds)
        a.await()
      }
      await { value + 1 }
    }
    mark.elapsedNow() shouldBe 500.milliseconds
    result shouldBe 6
  }

  @Test
  fun stackSafety() = runTest {
    resourceScope {
      val n = 10
      val result = _reset<Int> {
        for (i in 0 until n) {
          _shiftC { k -> (k(null).also(::println) + i) }
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
    val result = _reset<Int> {
      _shiftC { k ->
        (1..n).sumOf { k(it) }
      }
      1
    }
    result shouldBe n
  }

  @Test
  fun shiftTests() = runTest {
    10 + _reset<Int> {
      2 + _shiftC<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    10 * _reset<Int> {
      _shiftC {
        5 * _shiftC<Int, _> { f -> f(1) + 1 }
      }
    } shouldBe 60
    run {
      @Composable
      fun <R> _Reset<R>.f(x: R) = _shiftC<R, R> { k -> k(k(x)) }
      1 + _reset<Int> { 10 + f(100) }
    } shouldBe 121
    run {
      @Composable
      fun _Reset<String>.x() = _shiftC { f -> "a" + f("") }
      _reset<String> {
        _shiftC { x() }
      }
    } shouldBe "a"
    _reset<String> {
      _shiftC { g ->
        _shiftC { f -> "a" + f("") }
        "b"
      }
    } shouldBe "ab"
    _reset<String> {
      _shiftC { g ->
        _shiftC { f -> "a" + f("") }
        g("b")
      }
    } shouldBe "ab"
  }

  @Test
  fun lazyShifts() = runTest {
    _reset<Int> {
      val x = _nestedReset(this) {
        _shiftC { k -> k(1) + k(1) + 1 }
      }
      x + 1
    } shouldBe 4
  }
}