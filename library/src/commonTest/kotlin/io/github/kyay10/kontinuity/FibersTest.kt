package io.github.kyay10.kontinuity

import kotlin.contracts.contract
import kotlin.test.Test

class FibersTest {
  private val printed = StringBuilder()
  suspend fun Scheduler2.user1() {
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

  suspend fun user2() {
    val f = Fibre.create {
      printed.appendLine("Hello from fiber")
      suspend()
      printed.appendLine("back again")
      42
    }
    f.resume()
    printed.appendLine("In our main thread")
    f.resume()
    check(f.isDone())
    printed.appendLine("Again in our main thread")
    printed.appendLine("Fiber is done: ${f.isDone()}")
    printed.appendLine("Result is: ${f.result}")
  }

  @Test
  fun example() = runTestCC {
    user2()
    printed.toString() shouldEq """
      |Hello from fiber
      |In our main thread
      |back again
      |Again in our main thread
      |Fiber is done: true
      |Result is: 42
      |
    """.trimMargin()
    printed.clear()
    scheduler2 { user1() }
    printed.toString() shouldEq """
      |Hello from main
      |Hello from fork
      |Hello from main 2
      |World from fork 2
      |Hello from main 3
      |
    """.trimMargin()
  }
}

interface Fibre<A> {
  suspend fun resume()
  val isDone: Boolean

  companion object {
    fun <A> create(block: suspend Suspendable.() -> A): Fibre<A> {
      // set up the communication channel between the two components
      val fiber = CanResume<A>()
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

  interface HasResult<A> {
    val result: A
  }
}

fun <A> Fibre<A>.isDone(): Boolean {
  contract { returns(true) implies (this@isDone is Fibre.HasResult<A>) }
  return isDone
}

class CanResume<A> : Fibre<A>, Fibre.HasResult<A> {
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

  private var k: (suspend () -> Unit)? = null
  override suspend fun resume() {
    check(!isDone) { "Can't resume this fiber anymore" }
    val k = checkNotNull(k) { "This fiber is not yet set up" }
    this.k = null
    k()
  }

  fun next(k: suspend () -> Unit) {
    this.k = k
  }
}

interface Scheduler2 {
  fun fastFork(task: suspend Scheduler2.() -> Unit)

  // Since we only run on one thread, we need yield to allow cooperative multitasking
  suspend fun yield()
}

suspend fun scheduler2(block: suspend Scheduler2.() -> Unit) = runQueue<Task, _> {
  fun Handler<Unit>.scheduler(): Scheduler2 = object : Scheduler2 {
    override fun fastFork(task: suspend Scheduler2.() -> Unit) {
      enqueue { handle { task(scheduler()) } }
    }

    override suspend fun yield() = useOnce { enqueue { it(Unit) } }
  }
  // we can't run the scheduler in pure since the continuation that contains
  // the call to pure might be discarded by one fork, while another one
  // is still alive.
  handle { block(scheduler()) }
  // this is safe since all tasks container the scheduler prompt marker
  dequeueAll { it() }
}

fun interface Suspendable {
  suspend fun suspend()
}

typealias Task = suspend () -> Unit