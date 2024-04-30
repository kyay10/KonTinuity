import androidx.compose.runtime.Composable
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
  fun nestedContinuations() = runTest {
    reset<List<Int>> {
      yield(1)
      yieldAll(reset<List<Int>, _> {
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
}

fun stackSafeIteration(): Int = when (platform) {
  Platform.JVM -> 20_000
  else -> 1000
}