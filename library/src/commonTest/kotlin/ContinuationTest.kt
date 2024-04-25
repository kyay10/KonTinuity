import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import arrow.core.raise.Raise
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

// Minor issue: if your function returns Unit, it *must* be marked with @NonRestartableComposable
// Or it can return a different value
context(Reset<List<T>>, Raise<Unit>)
@NonRestartableComposable
@Composable
fun <T> yield(x: T) { shift { k -> listOf(x) + k(Unit) } }
context(Reset<List<T>>, Raise<Unit>)
@NonRestartableComposable
@Composable
fun <T> yieldAll(xs: List<T>) { shift { k -> xs + k(Unit) } }

class ContinuationTest {
  context(Raise<Unit>)
  @Composable
  fun Reset<Int>.foo(): Int = shift { k -> k(k(k(7))) } + 1

  context(Raise<Unit>)
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
}