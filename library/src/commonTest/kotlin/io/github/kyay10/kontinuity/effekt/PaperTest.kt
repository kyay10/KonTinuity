package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
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

  context(_: Fiber<Region>, _: Async<Region>, _: MultishotScope<Region>)
  suspend fun <Region> asyncExample() {
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

context(amb: Amb<Region>, exc: Exc<Region>, _: MultishotScope<Region>)
private suspend fun <Region> drunkFlip(): String {
  val caught = amb.flip()
  val heads = if (caught) amb.flip() else exc.raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

class Collect<R, IR, OR>(p: HandlerPrompt<List<R>, IR, OR>) : Amb<IR>, Handler<List<R>, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> collect(block: suspend context(NewScope<Region>) Amb<NewRegion>.() -> R): List<R> = handle {
  listOf(block(Collect(this)))
}

interface Fiber<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun suspend()

  context(_: MultishotScope<Region>)
  suspend fun fork(): Boolean

  context(_: MultishotScope<Region>)
  suspend fun exit(): Nothing
}

context(_: MultishotScope<Region>)
suspend inline fun <Region> Fiber<Region>.forked(p: () -> Unit) {
  if (fork()) {
    p()
    exit()
  }
}

context(fiber: Fiber<Region>, _: MultishotScope<Region>)
suspend fun <Region> suspend() = fiber.suspend()

interface Async<Region> {
  context(_: MultishotScope<Region>)
  suspend fun <T> async(body: suspend context(MultishotScope<Region>) () -> T): Promise<T, Region>
  interface Promise<T, in Region> {
    context(_: MultishotScope<Region>)
    suspend fun await(): T
  }
}

context(async: Async<Region>, _: MultishotScope<Region>)
suspend fun <T, Region> async(body: suspend context(MultishotScope<Region>) () -> T): Async.Promise<T, Region> = async.async(body)

class Poll<Region>(
  val state: StateScope,
  val fiber: Fiber<Region>,
) : Async<Region> {
  private class PromiseImpl<T, Region>(val fiber: Fiber<Region>, val field: StateScope.Field<Option<T>>) : Async.Promise<T, Region> {
    context(_: MultishotScope<Region>)
    override tailrec suspend fun await(): T = when (val v = field.get()) {
      is None -> {
        fiber.suspend()
        await()
      }

      is Some -> v.value
    }
  }

  context(_: MultishotScope<Region>)
  override suspend fun <T> async(body: suspend context(MultishotScope<Region>) () -> T): Async.Promise<T, Region> {
    val p = state.field<Option<T>>(None)
    fiber.forked {
      p.set(Some(body()))
    }
    return PromiseImpl(fiber, p)
  }
}

context(state: StateScope, fiber: Fiber<Region>, _: MultishotScope<Region>)
inline fun <Region> poll(block: Async<Region>.() -> Unit) = block(Poll(state, fiber))

context(_: MultishotScope<Region>)
suspend fun <R, Region> firstResult(block: suspend context(Amb<NewRegion>, Exc<NewRegion>, NewScope<Region>) () -> R): Option<R> = backtrack(block)

class Scheduler<IR, OR>(prompt: StatefulPrompt<Unit, Queue<OR>, IR, OR>) : Fiber<IR>, StatefulHandler<Unit, Queue<OR>, IR, OR> by prompt {
  context(_: MultishotScope<IR>)
  override suspend fun exit(): Nothing = discard {}

  context(_: MultishotScope<IR>)
  override suspend fun fork(): Boolean = use { resume ->
    get().add(0) { resume(false) }
    get().add(0) { resume(true) }
    run()
  }

  context(_: MultishotScope<IR>)
  override suspend fun suspend() = use { resume ->
    get().add { resume(Unit) }
    run()
  }
}

context(_: MultishotScope<Region>)
suspend fun <Region> scheduler(block: suspend context(NewScope<Region>) Fiber<NewRegion>.() -> Unit) =
  handleStateful(mutableListOf(), Queue<Region>::toMutableList) {
    Scheduler(this).block()
  }

context(_: MultishotScope<OR>)
private suspend fun <IR, OR> StatefulHandler<Unit, Queue<OR>, IR, OR>.run() {
  val q = get()
  if (q.isNotEmpty()) {
    q.removeFirst()()
  }
}

typealias Queue<Region> = MutableList<suspend context(MultishotScope<Region>) () -> Unit>

fun interface Input<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun read(): Char
}

context(input: Input<Region>, _: MultishotScope<Region>)
suspend fun <Region> read(): Char = input.read()