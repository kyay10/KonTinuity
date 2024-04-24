import androidx.compose.runtime.Composable
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
fun <T> Reset<List<T>>.yield(x: T) = shift { k -> listOf(x) + k(Unit) }

class ContinuationTest {
  @Test
  fun simpleContinuations() = runTest {
    // Examples from https://en.wikipedia.org/wiki/Delimited_continuation
    reset<Int> {
      maybe {
        val value by shift { k -> k(5) }
        val value2 by shift { k -> k(value + 1) }
        value2
      }
    } * 2 shouldBe 12
    reset {
      maybe {
        val value by shift { k -> k(k(4)) }
        value * 2
      }
    } + 1 shouldBe 17
    reset {
      maybe {
        yield(1).bind()
        yield(2).bind()
        yield(3).bind()
        emptyList()
      }
    } shouldBe listOf(1, 2, 3)
    // Example from https://www.scala-lang.org/old/node/2096
    @Composable
    fun Reset<Int>.foo(): Maybe<Int> = maybe {
      1 + shift { k -> k(k(k(7))) }.bind()
    }

    @Composable
    fun Reset<Int>.bar(): Maybe<Int> = maybe {
      2 * foo().bind()
    }

    suspend fun baz(): Int = reset {
      bar()
    }
    baz() shouldBe 70
  }

  @Test
  fun nestedContinuations() = runTest {}

  @OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
  @Test
  fun await() = runTest {
    val mark = testTimeSource.markNow()
    val a = async {
      delay(500.milliseconds)
      5
    }
    val result = reset {
      maybe {
        val value by await {
          delay(200.milliseconds)
          a.await()
        }
        val value2 by await { value + 1 }
        value2
      }
    }
    mark.elapsedNow() shouldBe 500.milliseconds
    result shouldBe 6
  }
}