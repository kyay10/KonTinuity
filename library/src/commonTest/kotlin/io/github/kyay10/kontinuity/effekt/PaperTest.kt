package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PaperTest {
  @Test
  fun ex4dot1() = runTest {
    runCC {
      val res = collect {
        maybe {
          drunkFlip()
        }
      }
      res shouldBe listOf(Some("Heads"), Some("Tails"), None)
    }
    runCC {
      val res = maybe {
        collect {
          drunkFlip()
        }
      }
      res shouldBe None
    }
  }

  @Test
  fun ex4dot3() = runTest {
    runCC {
      val res = collect {
        var x = 0
        { x } // we force x to be captured in a closure
        if (flip()) x = 2
        x
      }
      res shouldBe listOf(2, 2)
    }
    runCC {
      val res = collect {
        var x = 0
        if (flip()) x = 2
        x
      }
      res shouldBe listOf(2, 0)
    }
    runCC {
      val res = collect {
        region {
          var x by field(0)
          if (flip()) x = 2
          x
        }
      }
      res shouldBe listOf(2, 0)
    }
    runCC {
      val res = region {
        collect {
          var x by field(0)
          if (flip()) x = 2
          x
        }
      }
      res shouldBe listOf(2, 2)
    }
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
  fun ex4dot5dot4() = runTest {
    runCC {
      firstResult {
        drunkFlip()
      } shouldBe Some("Heads")
    }
  }

  @Test
  fun ex4dot5dot6() = runTest {
    runCC {
      region {
        scheduler {
          poll {
            asyncExample()
          }
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

class Poll(
  val state: StateScope,
  val fiber: Fiber,
) : Async {
  private class PromiseImpl<T>(val fiber: Fiber, val field: StateScope.OptionalField<T>) : Async.Promise<T> {
    override tailrec suspend fun await(): T = field.getOrPut {
      fiber.suspend()
      return await()
    }
  }

  override suspend fun <T> async(body: suspend () -> T): Async.Promise<T> {
    val p = state.field<T>()
    fiber.forked { p.value = body() }
    return PromiseImpl(fiber, p)
  }
}

context(state: StateScope, fiber: Fiber)
suspend inline fun poll(block: suspend Async.() -> Unit) =
  block(Poll(state, fiber))

suspend fun <R> firstResult(block: suspend context(Amb, Exc) () -> R): Option<R> = backtrack(block)

class Scheduler(prompt: StatefulPrompt<Unit, Queue>) : Fiber, StatefulHandler<Unit, Queue> by prompt {
  override suspend fun exit(): Nothing = discard {}
  override suspend fun fork(): Boolean = use { resume ->
    value.add(0) { resume(false) }
    value.add(0) { resume(true) }
    run()
  }

  override suspend fun suspend() = use { resume ->
    value.add { resume(Unit) }
    run()
  }
}

suspend fun scheduler(block: suspend Fiber.() -> Unit) = handleStateful(mutableListOf(), Queue::toMutableList) {
  Scheduler(this).block()
}

private suspend fun StatefulHandler<Unit, Queue>.run() {
  val q = value
  if (q.isNotEmpty()) {
    q.removeFirst()()
  }
}

typealias Queue = MutableList<suspend () -> Unit>

fun interface Input {
  suspend fun read(): Char
}

context(input: Input)
suspend fun read(): Char = input.read()