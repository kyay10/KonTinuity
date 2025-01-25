package effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.identity
import io.kotest.matchers.shouldBe
import pushReader
import runCC
import runTest
import kotlin.collections.plus
import kotlin.test.Test

class PaperTest {
  @Test
  fun ex4dot1() = runTest {
    runCC {
      val res = collect {
        maybe {
          drunkFlip(this@collect, this@maybe)
        }
      }
      res shouldBe listOf(Some("Heads"), Some("Tails"), None)
    }
    runCC {
      val res = maybe {
        collect {
          drunkFlip(this@collect, this@maybe)
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

  suspend fun asyncExample(f: Fiber, a: Async) {
    val str = buildString {
      with(f) {
        with(a) {
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
      }
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
        drunkFlip(this, this)
      } shouldBe Some("Heads")
    }
  }

  @Test
  fun ex4dot5dot6() = runTest {
    runCC {
      region {
        scheduler {
          poll(this@region, this) {
            asyncExample(this@scheduler, this)
          }
        }
      }
    }
  }
}

private suspend fun drunkFlip(amb: Amb, exc: Exc): String {
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

interface Async {
  suspend fun <T> async(body: suspend () -> T): Promise<T>
  interface Promise<T> {
    suspend fun await(): T
  }
}

interface Poll : Async {
  val state: StateScope
  val fiber: Fiber

  private class PromiseImpl<T>(val fiber: Fiber, val field: StateScope.Field<Option<T>>) : Async.Promise<T> {
    override tailrec suspend fun await(): T = when (val v = field.get()) {
      is None -> {
        fiber.suspend()
        await()
      }

      is Some -> v.value
    }
  }

  override suspend fun <T> async(body: suspend () -> T): Async.Promise<T> {
    val p = state.field<Option<T>>(None)
    fiber.forked {
      p.set(Some(body()))
    }
    return PromiseImpl(fiber, p)
  }
}

suspend inline fun poll(state: StateScope, fiber: Fiber, block: suspend Async.() -> Unit) = block(object : Poll {
  override val state = state
  override val fiber = fiber
})

suspend fun <R> firstResult(block: suspend AmbExc.() -> R): Option<R> = handle {
  Some(block(Backtrack(this)))
}

class Scheduler(prompt: StatefulPrompt<Unit, Queue>) : Fiber, StatefulHandler<Unit, Queue> by prompt {
  override suspend fun exit(): Nothing = discard {}
  override suspend fun fork(): Boolean = use { resume ->
    run(listOf(suspend { resume(true) }, suspend { resume(false) }) + get())
  }

  override suspend fun suspend() = use { resume ->
    run(get() + { resume(Unit) })
  }
}

suspend fun scheduler(block: suspend Fiber.() -> Unit) = handleStateful(emptyList<suspend () -> Unit>(), ::identity) {
  Scheduler(this).block()
}

private suspend fun StatefulHandler<Unit, Queue>.run(q: Queue) {
  if (q.isNotEmpty()) {
    reader.pushReader(q.drop(1)) {
      q.first()()
    }
  }
}

typealias Queue = List<suspend () -> Unit>

interface Input {
  suspend fun read(): Char
}