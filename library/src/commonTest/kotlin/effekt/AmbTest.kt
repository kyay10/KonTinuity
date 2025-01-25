package effekt

import arrow.core.None
import arrow.core.Some
import io.kotest.matchers.shouldBe
import runCC
import runTest
import kotlin.test.Test

class AmbTest {
  suspend fun drunkFlip(amb: Amb, exc: Exc): String {
    val heads = if (amb.flip()) amb.flip() else exc.raise("We dropped the coin.")
    return if (heads) "Heads" else "Tails"
  }

  @Test
  fun drunkFlipTest() = runTest {
    runCC {
      ambList {
        maybe {
          drunkFlip(this@ambList, this)
        }
      }
    } shouldBe listOf(Some("Heads"), Some("Tails"), None)
    runCC {
      maybe {
        ambList {
          drunkFlip(this, this@maybe)
        }
      }
    } shouldBe None
  }

  @Test
  fun example() = runTest {
    val printed = StringBuilder()
    runCC {
      ambList {
        printed.appendLine("Trying to flip a coin...")
        if (!flip()) {
          printed.appendLine("We dropped the coin")
          -1
        } else {
          printed.appendLine("We caught the coin")
          if (flip()) {
            printed.appendLine("That's heads")
            0
          } else {
            printed.appendLine("That's tails")
            1
          }
        }
      }
    } shouldBe listOf(0, 1, -1)
    printed.toString() shouldBe """
      |Trying to flip a coin...
      |We caught the coin
      |That's heads
      |That's tails
      |We dropped the coin
      |
    """.trimMargin()
  }
}

interface Amb {
  suspend fun flip(): Boolean
}

class AmbList<E>(p: HandlerPrompt<List<E>>) : Handler<List<E>> by p, Amb {
  override suspend fun flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

suspend fun <E> ambList(block: suspend Amb.() -> E): List<E> = handle {
  listOf(block(AmbList(this)))
}