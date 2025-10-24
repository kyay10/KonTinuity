package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.kotest.matchers.shouldBe
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runTest
import kotlin.test.Test

class AmbTest {
  context(amb: Amb<IR>, exc: Exc<IR>, _: MultishotScope<IR>)
  suspend fun <IR> drunkFlip(): String {
    val heads = if (amb.flip()) amb.flip() else exc.raise("We dropped the coin.")
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

fun interface Amb<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun flip(): Boolean
}

context(amb: Amb<Region>, _: MultishotScope<Region>)
suspend fun <Region> flip(): Boolean = amb.flip()

class AmbList<E, in IR, OR>(p: HandlerPrompt<List<E>, IR, OR>) : Handler<List<E>, IR, OR> by p, Amb<IR> {
  context(_: MultishotScope<IR>)
  override suspend fun flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

// TODO sometimes triggers KT-81618. Update Kotlin version and see if it persists.
context(_: MultishotScope<Region>)
suspend fun <E, Region> ambList(block: suspend context(NewScope<Region>) Amb<NewRegion>.() -> E): List<E> = handle {
  listOf(block(AmbList(this)))
}