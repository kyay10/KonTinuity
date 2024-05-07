import Reset.Companion.lazyReset
import androidx.compose.runtime.Composable
import arrow.fx.coroutines.resourceScope
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

@Composable
fun <T> Reset<List<T>>.yield(x: T) {
  shift { k -> listOf(x) + k(Unit) }
}

@Composable
fun <T> Reset<List<T>>.yieldAll(xs: List<T>) {
  shift { k -> xs + k(Unit) }
}

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
      val act = shift<@Composable () -> Unit, _> { k ->
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
      val act = control<@Composable () -> Unit, _> { k ->
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
  fun typeChanging() = runTest {
    // From https://web.archive.org/web/20200713053410/http://lambda-the-ultimate.org/node/606
    resourceScope {
      val f = lazyReset<Cont<@Composable () -> Nothing, Nothing>> {
        controlAndChangeType({ k -> k }) { it() }
      }
      reset {
        await {
          f { control { false } }
        }
        // The compiler knows that this will never run!
        true
      } shouldBe false
    }
    reset {
      val f: @Composable () -> Nothing = control { k ->
        k { control { false } }
        true
      }
      f()
    } shouldBe false
    reset {
      val f: @Composable () -> Nothing = shift { k ->
        k {
          shift { false }
        }
        true
      }
      f()
    } shouldBe true

    resourceScope {
      val cont: Cont<@Composable Reset<Boolean>.() -> Boolean, Boolean> = lazyReset {
        shiftAndChangeType<_, _, Boolean>({ k -> k }) { it() }
      }
      reset {
        shift { k ->
          cont { shift { false } }
          k(Unit)
        }
        true
      } shouldBe true
      reset {
        shift { k ->
          k(Unit)
          cont { this@reset.shift { false } }
          k(Unit)
        }
        true
      } shouldBe false
    }

    resourceScope {
      reset {
        reset { shiftWith(false) }
        true
      } shouldBe true
      reset outer@{
        reset<Nothing> { this@outer.shiftWith(false) }
        true
      } shouldBe false
    }

    resourceScope {
      val f = lazyReset<Cont<@Composable () -> Nothing, Nothing>> {
        controlAndChangeType({ k -> k }) { it() }
      }
      try {
        reset {
          await {
            f { throw RuntimeException("Hello") }
          }
        }
      } catch (e: RuntimeException) {
        e.message shouldBe "Hello"
      }
    }
  }

  @Test
  fun nestedContinuations() = runTest {
    reset<List<Int>> {
      yield(1)
      yieldAll(reset<List<Int>> {
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
      await { value + 1 }
    }
    mark.elapsedNow() shouldBe 500.milliseconds
    result shouldBe 6
  }

  @Test
  fun stackSafety() = runTest {
    val n = stackSafeIteration()
    val result = reset<Int> {
      for (i in 0 until n) {
        shift { k -> k(Unit) + i }
      }
      0
    }
    result shouldBe n * (n - 1) / 2
  }

  @Test
  fun manyIterations() = runTest {
    val n = 200_000
    // Bottleneck is coroutines!
    val result = reset<Int> {
      shift { k ->
        (1..n).sumOf { k(it) }
      }
      1
    }
    result shouldBe n
  }

  @Test
  fun generalisedTests() = runTest {
    10 + reset<Int> {
      2 + shift<Int, _> { k -> 100 + k(k(3)) }
    } shouldBe 117
    10 * reset<Int> {
      shiftWith(reset<Int> {
        5 * shift<Int, _> { f -> f(1) + 1 }
      })
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
        shiftWith(x())
      }
    } shouldBe "a"
  }
}

fun stackSafeIteration(): Int = when (platform) {
  Platform.JVM -> 20_000
  else -> 1000
}