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

  context(f: Fiber, a: Async)
  suspend fun MultishotScope.asyncExample() {
    val str = buildString {
      val p = async {
        appendLine("Async 1")
        suspend()
        appendLine("Async 2")
        suspend()
        42
      }
      appendLine("Main")
      val r = with(p) { await() }
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
private suspend fun MultishotScope.drunkFlip(): String {
  val caught = flip()
  val heads = if (caught) flip() else raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

class Collect<R>(p: HandlerPrompt<List<R>>) : Amb, Handler<List<R>> by p {
  override suspend fun MultishotScope.flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

suspend fun <R> MultishotScope.collect(block: suspend context(Amb) MultishotScope.() -> R): List<R> = handle {
  listOf(block(Collect(given<HandlerPrompt<List<R>>>()), this))
}

interface Fiber {
  suspend fun MultishotScope.suspend()
  suspend fun MultishotScope.fork(): Boolean
  suspend fun MultishotScope.exit(): Nothing
  suspend fun MultishotScope.forked(p: suspend MultishotScope.() -> Unit) {
    if (fork()) {
      p()
      exit()
    }
  }
}

context(fiber: Fiber)
suspend fun MultishotScope.suspend() = with(fiber) { suspend() }

interface Async {
  suspend fun <T> MultishotScope.async(body: suspend MultishotScope.() -> T): Promise<T>
  interface Promise<T> {
    suspend fun MultishotScope.await(): T
  }
}

context(async: Async)
suspend fun <T> MultishotScope.async(body: suspend MultishotScope.() -> T): Async.Promise<T> = with(async) { async(body) }

suspend fun <T> MultishotScope.await(p: Async.Promise<T>): T = with(p) { await() }

class Poll(
  val state: StateScope,
  val fiber: Fiber,
) : Async {
  private class PromiseImpl<T>(val fiber: Fiber, val field: StateScope.Field<Option<T>>) : Async.Promise<T> {
    override tailrec suspend fun MultishotScope.await(): T = when (val v = field.get()) {
      is None -> {
        with(fiber) { suspend() }
        await()
      }

      is Some -> v.value
    }
  }

  override suspend fun <T> MultishotScope.async(body: suspend MultishotScope.() -> T): Async.Promise<T> {
    val p = state.field<Option<T>>(None)
    with(fiber) {
      forked {
        p.set(Some(body()))
      }
    }
    return PromiseImpl(fiber, p)
  }
}

context(state: StateScope, fiber: Fiber)
suspend inline fun MultishotScope.poll(block: suspend context(Async) MultishotScope.() -> Unit) =
  block(Poll(state, fiber), this)

suspend fun <R> MultishotScope.firstResult(block: suspend context(Amb, Exc) MultishotScope.() -> R): Option<R> = backtrack(block)

class Scheduler(prompt: StatefulPrompt<Unit, Queue>) : Fiber, StatefulHandler<Unit, Queue> by prompt {
  override suspend fun MultishotScope.exit(): Nothing = discard {}
  override suspend fun MultishotScope.fork(): Boolean = use { resume ->
    get().add(0) { resume(false) }
    get().add(0) { resume(true) }
    run()
  }

  override suspend fun MultishotScope.suspend() = use { resume ->
    get().add { resume(Unit) }
    run()
  }
}

suspend fun MultishotScope.scheduler(block: suspend context(Fiber) MultishotScope.() -> Unit) = handleStateful(mutableListOf(), Queue::toMutableList) {
  block(Scheduler(given<StatefulPrompt<Unit, Queue>>()), this)
}

context(s: StatefulHandler<Unit, Queue>)
private suspend fun MultishotScope.run() {
  val q = get()
  if (q.isNotEmpty()) {
    q.removeFirst()()
  }
}

typealias Queue = MutableList<suspend MultishotScope.() -> Unit>

fun interface Input {
  suspend fun MultishotScope.read(): Char
}

context(input: Input)
suspend fun MultishotScope.read(): Char = with(input) { read() }