package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.*
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FibersTest {
  private val printed = StringBuilder()

  context(_: MultishotScope<IR>)
  suspend fun <IR, OR> Scheduler2<IR, OR>.user1() {
    fastFork {
      printed.appendLine("Hello from fork")
      yield()
      printed.appendLine("World from fork 2")
    }
    printed.appendLine("Hello from main")
    yield()
    printed.appendLine("Hello from main 2")
    yield()
    printed.appendLine("Hello from main 3")
  }

  context(_: MultishotScope<*>)
  suspend fun user2() {
    val f = Fibre.create<_, Any?> {
      printed.appendLine("Hello from fiber")
      suspend()
      printed.appendLine("back again")
      42
    }
    f.resume()
    printed.appendLine("In our main thread")
    f.resume()
    printed.appendLine("Again in our main thread")
    printed.appendLine("Fiber is done: ${f.isDone}")
    printed.appendLine("Result is: ${f.result}")
  }

  @Test
  fun example() = runTest {
    runCC {
      user2()
    }
    printed.toString() shouldBe """
      |Hello from fiber
      |In our main thread
      |back again
      |Again in our main thread
      |Fiber is done: true
      |Result is: 42
      |
    """.trimMargin()
    printed.clear()
    runCC {
      scheduler2 {
        user1()
      }
    }
    printed.toString() shouldBe """
      |Hello from main
      |Hello from fork
      |Hello from main 2
      |World from fork 2
      |Hello from main 3
      |
    """.trimMargin()
  }
}

interface Fibre<A, in Region> {
  context(_: MultishotScope<Region>)
  suspend fun resume()
  val isDone: Boolean
  val result: A

  companion object {
    fun <A, Region> create(block: suspend context(NewScope<Region>) Suspendable<NewRegion>.() -> A): Fibre<A, Region> {
      // set up the communication channel between the two components
      val fiber = CanResume<A, Region>()
      // create first continuation by installing a ScheduledSuspendable handler
      fiber.next {
        handle {
          fiber.returnWith(block {
            useOnce { k ->
              fiber.next { k(Unit) }
            }
          })
        }
      }
      return fiber
    }
  }
}

class CanResume<A, Region> : Fibre<A, Region> {
  private data object EmptyResult

  override val isDone get() = res != EmptyResult

  @Suppress("UNCHECKED_CAST")
  private var res: A = EmptyResult as A
  override val result: A
    get() {
      check(isDone) { "This fiber is not yet done, can't get result" }
      return res
    }

  fun returnWith(value: A) {
    res = value
  }

  private var k: (suspend context(MultishotScope<Region>) () -> Unit)? = null

  context(_: MultishotScope<Region>)
  override suspend fun resume() {
    check(!isDone) { "Can't resume this fiber anymore" }
    val k = checkNotNull(k) { "This fiber is not yet set up" }
    this.k = null
    k()
  }

  fun next(k: suspend context(MultishotScope<Region>) () -> Unit) {
    this.k = k
  }
}

class Scheduler2<IR, OR>(private val tasks: ArrayDeque<Task<OR>>, prompt: HandlerPrompt<Unit, IR, OR>) :
  Handler<Unit, IR, OR> by prompt {
  context(_: MultishotScope<IR>)
  suspend fun fork(): Boolean = useWithFinal { (k, final) ->
    tasks.addLast { final(true) }
    k(false)
  }

  context(_: MultishotScope<IR>)
  suspend inline fun forkFlipped(task: () -> Unit) {
    if (!fork()) {
      task()
      discardWithFast(Result.success(Unit))
    }
  }

  context(_: MultishotScope<IR>)
  suspend inline fun fork(task: () -> Unit) {
    // TODO this reveals an inefficiency in the SplitSeq code
    //  because here the frames up to the prompt are never used
    //  so we should never have to copy them, but seemingly we copy
    //  at least the SplitSeq elements by turning them into Segments
    //  so maybe we can delay segment creation?
    // Something something reflection without remorse?
    if (fork()) {
      task()
      discardWithFast(Result.success(Unit))
    }
  }

  fun fastFork(task: suspend context(NewScope<OR>) Scheduler2<NewRegion, OR>.() -> Unit) {
    tasks.addLast {
      handle {
        task(Scheduler2(tasks, this))
      }
    }
  }

  // Since we only run on one thread, we also need yield in the scheduler
  // to allow cooperative multitasking
  context(_: MultishotScope<IR>)
  suspend fun yield() = useOnce {
    tasks.addLast { it(Unit) }
  }

  context(_: MultishotScope<IR>)
  suspend fun yieldAndRepush() = useWithFinal { (_, final) ->
    tasks.addLast { final(Unit) }
  }
}

// we can't run the scheduler in pure since the continuation that contains
// the call to pure might be discarded by one fork, while another one
// is still alive.
context(_: MultishotScope<Region>)
suspend fun <Region> ArrayDeque<Task<Region>>.run() {
  while (isNotEmpty()) {
    removeFirst()()
  }
}

context(_: MultishotScope<Region>)
suspend fun <Region> scheduler2(block: suspend context(NewScope<Region>) Scheduler2<NewRegion, Region>.() -> Unit) {
  val tasks = ArrayDeque<Task<Region>>()
  handle {
    block(Scheduler2(tasks, this))
  }
  // this is safe since all tasks container the scheduler prompt marker
  tasks.run()
}

fun interface Suspendable<in Region> {
  context(_: MultishotScope<Region>)
  suspend fun suspend()
}

typealias Task<Region> = suspend context(MultishotScope<Region>) () -> Unit