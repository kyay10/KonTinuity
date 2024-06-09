package effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import runCC
import kotlin.collections.plus
import kotlin.test.Test

class HandlerTest {
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

interface Exc {
  suspend fun raise(msg: String): Nothing
}

interface Amb {
  suspend fun flip(): Boolean
}

interface Choose {
  suspend fun <A> choose(first: A, second: A): A
}

private suspend fun drunkFlip(amb: Amb, exc: Exc): String {
  val caught = amb.flip()
  val heads = if (caught) amb.flip() else exc.raise("We dropped the coin.")
  return if (heads) "Heads" else "Tails"
}

fun interface Maybe<R> : Exc, Handler<R, Option<R>> {
  override suspend fun unit(value: R): Option<R> = Some(value)
  override suspend fun raise(msg: String): Nothing = useAbort { None }
}

suspend fun <R> maybe(block: suspend Exc.() -> R): Option<R> = handle(::Maybe, block)

fun interface Collect<R> : Amb, Handler<R, List<R>> {
  override suspend fun unit(value: R): List<R> = listOf(value)
  override suspend fun flip(): Boolean = use { resume ->
    val ts = resume(true)
    val fs = resume(false)
    ts + fs
  }
}

suspend fun <R> collect(block: suspend Amb.() -> R): List<R> = handle(::Collect, block)

fun interface CollectChoose<R> : Collect<R>, Choose {
  override suspend fun <A> choose(first: A, second: A): A = if (flip()) first else second
}

suspend fun <R> collectChoose(block: suspend CollectChoose<R>.() -> R): List<R> = handle(::CollectChoose, block)

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

interface NonDetermined : Amb, Exc

interface Backtrack<R> : Amb, Handler<R, Option<R>> {
  override suspend fun flip(): Boolean = use { resume ->
    resume(true).onNone { return@use resume(false) }
  }
}

fun interface FirstResult<R> : NonDetermined, Maybe<R>, Backtrack<R>

suspend fun <R> firstResult(block: suspend NonDetermined.() -> R) = handle(::FirstResult, block)

interface Scheduler<R> : Fiber, Handler<R, Unit> {
  val queue: StateScope.Field<Queue>
  override suspend fun unit(value: R) {}
  override suspend fun exit(): Nothing = useAbort {}
  override suspend fun fork(): Boolean = use { resume ->
    queue.update { listOf(suspend { resume(true) }, suspend { resume(false) }) + it }
    run()
  }

  override suspend fun suspend() = use { resume ->
    queue.update { it + suspend { resume(Unit) } }
    run()
  }
}

suspend fun StateScope.scheduler(block: suspend Fiber.() -> Unit) {
  val queue = field(emptyList<suspend () -> Unit>())
  handle<Unit, Unit, Scheduler<Unit>>({
    object : Scheduler<Unit> {
      override val queue = queue
      override fun prompt(): ObscurePrompt<Unit> = it()
    }
  }, block)
}

private tailrec suspend fun Scheduler<*>.run() {
  val q = queue.get()
  if (q.isNotEmpty()) {
    val p = q.first()
    queue.set(q.drop(1))
    p()
    run()
  }
}

typealias Queue = List<suspend () -> Unit>

interface Input {
  suspend fun read(): Char
}