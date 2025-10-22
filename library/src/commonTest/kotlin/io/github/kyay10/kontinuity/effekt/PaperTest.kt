package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
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
          val x = field(0)
          if (flip()) x.set(2)
          x.get()
        }
      }
      res shouldBe listOf(2, 0)
    }
    runCC {
      val res = region {
        collect {
          val x = field(0)
          if (flip()) x.set(2)
          x.get()
        }
      }
      res shouldBe listOf(2, 2)
    }
  }

  context(_: Fiber, _: Async, _: MultishotScope)
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

context(amb: Amb, exc: Exc, _: MultishotScope)
private suspend fun drunkFlip(): String {
  val caught = amb.flip()
  val heads = if (caught) amb.flip() else exc.raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

class Collect<R>(p: HandlerPrompt<List<R>>) : Amb, Handler<List<R>> by p {
  context(_: MultishotScope)
  override suspend fun flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

context(_: MultishotScope)
suspend fun <R> collect(block: suspend context(MultishotScope) Amb.() -> R): List<R> = handle {
  listOf(block(Collect(this)))
}

interface Fiber {
  context(_: MultishotScope)
  suspend fun suspend()

  context(_: MultishotScope)
  suspend fun fork(): Boolean

  context(_: MultishotScope)
  suspend fun exit(): Nothing

  context(_: MultishotScope)
  suspend fun forked(p: suspend context(MultishotScope) () -> Unit) {
    if (fork()) {
      p()
      exit()
    }
  }
}

context(fiber: Fiber, _: MultishotScope)
suspend fun suspend() = fiber.suspend()

interface Async {
  context(_: MultishotScope)
  suspend fun <T> async(body: suspend context(MultishotScope) () -> T): Promise<T>
  interface Promise<T> {
    context(_: MultishotScope)
    suspend fun await(): T
  }
}

context(async: Async, _: MultishotScope)
suspend fun <T> async(body: suspend context(MultishotScope) () -> T): Async.Promise<T> = async.async(body)

class Poll(
  val state: StateScope,
  val fiber: Fiber,
) : Async {
  private class PromiseImpl<T>(val fiber: Fiber, val field: StateScope.Field<Option<T>>) : Async.Promise<T> {
    context(_: MultishotScope)
    override tailrec suspend fun await(): T = when (val v = field.get()) {
      is None -> {
        fiber.suspend()
        await()
      }

      is Some -> v.value
    }
  }

  context(_: MultishotScope)
  override suspend fun <T> async(body: suspend context(MultishotScope) () -> T): Async.Promise<T> {
    val p = state.field<Option<T>>(None)
    fiber.forked {
      p.set(Some(body()))
    }
    return PromiseImpl(fiber, p)
  }
}

context(state: StateScope, fiber: Fiber, _: MultishotScope)
suspend inline fun poll(block: suspend context(MultishotScope) Async.() -> Unit) =
  block(Poll(state, fiber))

context(_: MultishotScope)
suspend fun <R> firstResult(block: suspend context(Amb, Exc, MultishotScope) () -> R): Option<R> = backtrack(block)

class Scheduler(prompt: StatefulPrompt<Unit, Queue>) : Fiber, StatefulHandler<Unit, Queue> by prompt {
  context(_: MultishotScope)
  override suspend fun exit(): Nothing = discard {}

  context(_: MultishotScope)
  override suspend fun fork(): Boolean = use { resume ->
    get().add(0) { resume(false) }
    get().add(0) { resume(true) }
    run()
  }

  context(_: MultishotScope)
  override suspend fun suspend() = use { resume ->
    get().add { resume(Unit) }
    run()
  }
}

context(_: MultishotScope)
suspend fun scheduler(block: suspend context(MultishotScope) Fiber.() -> Unit) =
  handleStateful(mutableListOf(), Queue::toMutableList) {
    Scheduler(this).block()
  }

context(_: MultishotScope)
private suspend fun StatefulHandler<Unit, Queue>.run() {
  val q = get()
  if (q.isNotEmpty()) {
    q.removeFirst()()
  }
}

typealias Queue = MutableList<suspend context(MultishotScope) () -> Unit>

fun interface Input {
  context(_: MultishotScope)
  suspend fun read(): Char
}

context(input: Input, _: MultishotScope)
suspend fun read(): Char = input.read()