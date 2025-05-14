package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runTest
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FibersTest {
  private val printed = StringBuilder()
  context(_: Scheduler2)
  suspend fun MultishotScope.user1() {
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

  suspend fun MultishotScope.user2() {
    val f = Fibre.create {
      printed.appendLine("Hello from fiber")
      suspend()
      printed.appendLine("back again")
      42
    }
    resume(f)
    printed.appendLine("In our main thread")
    resume(f)
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
  suspend fun MultishotScope.resume()
  val isDone: Boolean
  val result: A

  companion object {
    fun <A> create(block: suspend context(Suspendable) MultishotScope.() -> A): Fibre<A> {
      // set up the communication channel between the two components
      val fiber = CanResume<A>()
      // create first continuation by installing a ScheduledSuspendable handler
      fiber.next {
        handle {
          fiber.returnWith(block({
            useOnce { k ->
              fiber.next { k(Unit) }
            }
          }, this))
        }
      }
      return fiber
    }
  }
}

suspend fun MultishotScope.resume(f: Fibre<*>) = with(f) { resume() }

class CanResume<A> : Fibre<A> {
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

  private var k: (suspend MultishotScope.() -> Unit)? = null
  override suspend fun MultishotScope.resume() {
    check(!isDone) { "Can't resume this fiber anymore" }
    val k = checkNotNull(k) { "This fiber is not yet set up" }
    this@CanResume.k = null
    k()
  }

  fun next(k: suspend MultishotScope.() -> Unit) {
    this.k = k
  }
}

class Scheduler2(internal val tasks: ArrayDeque<Task>, prompt: HandlerPrompt<Unit>) :
  Handler<Unit> by prompt

context(s: Scheduler2)
suspend fun MultishotScope.fork(): Boolean = useWithFinal { (k, final) ->
  s.tasks.addLast { final(true) }
  k(false)
}

context(s: Scheduler2)
suspend inline fun MultishotScope.forkFlipped(task: Task) {
  if (!fork()) {
    task()
    discardWithFast(Result.success(Unit))
  }
}

context(s: Scheduler2)
suspend inline fun MultishotScope.fork(task: Task) {
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

context(s: Scheduler2)
fun fastFork(task: suspend context(Scheduler2) MultishotScope.() -> Unit) {
  s.tasks.addLast {
    handle {
      task(Scheduler2(s.tasks, given<HandlerPrompt<Unit>>()), this)
    }
  }
}

// Since we only run on one thread, we also need yield in the scheduler
// to allow cooperative multitasking
context(s: Scheduler2)
suspend fun MultishotScope.yield() = useOnce {
  s.tasks.addLast { it(Unit) }
}

context(s: Scheduler2)
suspend fun MultishotScope.yieldAndRepush() = useWithFinal { (_, final) ->
  s.tasks.addLast { final(Unit) }
}

// we can't run the scheduler in pure since the continuation that contains
// the call to pure might be discarded by one fork, while another one
// is still alive.
suspend fun MultishotScope.run(functions: ArrayDeque<Task>) {
  while (functions.isNotEmpty()) {
    functions.removeFirst()(this)
  }
}

suspend fun MultishotScope.scheduler2(block: suspend context(Scheduler2) MultishotScope.() -> Unit) {
  val tasks = ArrayDeque<Task>()
  handle {
    block(Scheduler2(tasks, given<HandlerPrompt<Unit>>()), this)
  }
  // this is safe since all tasks container the scheduler prompt marker
  run(tasks)
}

fun interface Suspendable {
  suspend fun MultishotScope.suspend()
}

context(s: Suspendable)
suspend fun MultishotScope.suspend() = with(s) { suspend() }

typealias Task = suspend MultishotScope.() -> Unit