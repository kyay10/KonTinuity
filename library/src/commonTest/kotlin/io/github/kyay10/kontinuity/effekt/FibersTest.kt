package io.github.kyay10.kontinuity.effekt

import io.kotest.matchers.shouldBe
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runTest
import kotlin.test.Test

class FibersTest {
  private val printed = StringBuilder()
  suspend fun Scheduler2.user1() {
    fork {
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

interface Fibre<A> {
  suspend fun resume()
  val isDone: Boolean
  val result: A

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
}

class CanResume<A> : Fibre<A> {
  override var isDone = false
  @Suppress("UNCHECKED_CAST")
  private var res: A = null as A
  override val result: A
    @Suppress("UNCHECKED_CAST")
    get() {
      check(isDone) { "This fiber is not yet done, can't get result" }
      return res
    }

  fun returnWith(value: A) {
    res = value
    isDone = true
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

class Scheduler2(private val tasks: ArrayDeque<Task>, prompt: HandlerPrompt<Unit>) :
  Handler<Unit> by prompt {
  suspend fun fork(): Boolean = useWithFinal { (k, final) ->
    tasks.addLast { final(true) }
    k(false)
  }

  suspend inline fun forkFlipped(task: Task) {
    if (!fork()) {
      task()
      discardWithFast(Result.success(Unit))
    }
  }

  suspend inline fun fork(task: Task) {
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

  fun fastFork(task: suspend Scheduler2.() -> Unit) {
    tasks.addLast {
      handle {
        task(Scheduler2(tasks, this))
      }
    }
  }

  // Since we only run on one thread, we also need yield in the scheduler
  // to allow cooperative multitasking
  suspend fun yield() = useOnce {
    tasks.addLast { it(Unit) }
  }
}

// we can't run the scheduler in pure since the continuation that contains
// the call to pure might be discarded by one fork, while another one
// is still alive.
suspend fun ArrayDeque<Task>.run() {
  while (isNotEmpty()) {
    removeFirst()()
  }
}

suspend fun scheduler2(block: suspend Scheduler2.() -> Unit) {
  val tasks = ArrayDeque<Task>()
  handle {
    block(Scheduler2(tasks, this))
  }
  // this is safe since all tasks container the scheduler prompt marker
  tasks.run()
}

fun interface Suspendable {
  suspend fun suspend()
}

typealias Task = suspend () -> Unit