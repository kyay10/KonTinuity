package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.buildListLocally
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PaperTest {
  @Test
  fun ex4dot1() = runTestCC {
    collect {
      maybe {
        drunkFlip()
      }
    } shouldBe listOf(Some("Heads"), Some("Tails"), None)
    maybe {
      collect {
        drunkFlip()
      }
    } shouldBe None
  }

  @Suppress("UnusedLambdaExpression")
  @Test
  fun ex4dot3() = runTestCC {
    collect {
      var x = 0
      { x } // we force x to be captured in a closure
      if (flip()) x = 2
      x
    } shouldBe listOf(2, 2)
    collect {
      var x = 0
      if (flip()) x = 2
      x
    } shouldBe listOf(2, 0)

    collect {
      region {
        var x by field(0)
        if (flip()) x = 2
        x
      }
    } shouldBe listOf(2, 0)
    region {
      collect {
        var x by field(0)
        if (flip()) x = 2
        x
      }
    } shouldBe listOf(2, 2)
  }

  context(f: Fiber, a: Async)
  suspend fun asyncExample() {
    val str = buildString {
      val p = async {
        appendLine("Async 1")
        suspend()
        appendLine("Async 2")
        suspend()
        42
      }
      appendLine("Main")
      val r = p.await()
      appendLine("Main with result $r")
    }
    str shouldBe """
      Async 1
      Main
      Async 2
      Main with result 42
      
    """.trimIndent()
  }

  @Test
  fun ex4dot5dot4() = runTestCC {
    firstResult {
      drunkFlip()
    } shouldBe Some("Heads")
  }

  @Test
  fun ex4dot5dot6() = runTestCC {
    region {
      scheduler {
        poll {
          asyncExample()
        }
      }
    }
  }
}

context(amb: Amb, exc: Exc)
private suspend fun drunkFlip(): String {
  val caught = amb.flip()
  val heads = if (caught) amb.flip() else exc.raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

class Collect<R>(p: HandlerPrompt<List<R>>) : Amb, Handler<List<R>> by p {
  override suspend fun flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

suspend fun <R> collect(block: suspend Amb.() -> R): List<R> = handle {
  listOf(block(Collect(this)))
}

interface Fiber {
  suspend fun suspend()
  suspend fun fork(): Boolean
  suspend fun exit(): Nothing
  suspend fun forked(p: suspend () -> Unit) {
    if (fork()) {
      p()
      exit()
    }
  }
}

context(fiber: Fiber)
suspend fun suspend() = fiber.suspend()

interface Async {
  suspend fun <T> async(body: suspend () -> T): Promise<T>
  interface Promise<T> {
    suspend fun await(): T
  }
}

context(async: Async)
suspend fun <T> async(body: suspend () -> T): Async.Promise<T> = async.async(body)

class Poll(state: Region, val fiber: Fiber) : Async, Region by state {
  private class PromiseImpl<T>(val fiber: Fiber, val field: Region.OptionalField<T>) : Async.Promise<T> {
    override tailrec suspend fun await(): T = field.getOrPut {
      fiber.suspend()
      return await()
    }
  }

  override suspend fun <T> async(body: suspend () -> T): Async.Promise<T> {
    val p = field<T>()
    fiber.forked { p.value = body() }
    return PromiseImpl(fiber, p)
  }
}

context(state: Region, fiber: Fiber)
suspend inline fun poll(block: suspend Async.() -> Unit) =
  block(Poll(state, fiber))

suspend fun <R> firstResult(block: suspend context(Amb, Exc) () -> R): Option<R> = backtrack(block)

suspend fun scheduler(block: suspend Fiber.() -> Unit) = buildListLocally<suspend () -> Unit> {
  handle {
    block(object : Fiber {
      override suspend fun exit(): Nothing = discard {}
      override suspend fun fork(): Boolean = use { resume ->
        add { resume(false) }
        add { resume(true) }
        removeLastOrNull()?.invoke()
      }

      override suspend fun suspend() = use { resume ->
        add(0) { resume(Unit) }
        removeLastOrNull()?.invoke()
      }
    })
  }
}