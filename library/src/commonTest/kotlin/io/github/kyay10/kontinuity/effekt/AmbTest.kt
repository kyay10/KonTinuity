package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import io.kotest.matchers.shouldBe
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runTest
import kotlin.test.Test

class AmbTest {
  context(amb: Amb, exc: Exc)
  suspend fun MultishotScope.drunkFlip(): String {
    val heads = if (flip()) flip() else raise("We dropped the coin.")
    return if (heads) "Heads" else "Tails"
  }

  @Test
  fun drunkFlipTest() = runTest {
    runCC {
      ambList {
        maybe {
          drunkFlip()
        }
      }
    } shouldBe listOf(Some("Heads"), Some("Tails"), None)
    runCC {
      maybe {
        ambList {
          drunkFlip()
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

fun interface Amb {
  suspend fun MultishotScope.flip(): Boolean
}

context(amb: Amb)
suspend fun MultishotScope.flip(): Boolean = with(amb) { flip() }

class AmbList<E>(p: HandlerPrompt<List<E>>) : Handler<List<E>> by p, Amb {
  override suspend fun MultishotScope.flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

suspend fun <E> MultishotScope.ambList(block: suspend context(Amb) MultishotScope.() -> E): List<E> = handle {
  listOf(block(AmbList(given<HandlerPrompt<List<E>>>()), this))
}