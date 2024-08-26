package effekt

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import runCC
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
      val suspend = CanSuspend(HandlerPrompt(), fiber)
      // create first continuation by installing a ScheduledSuspendable handler
      fiber.next {
        suspend.prompt().handle { fiber.returnWith(suspend.block()) }
      }
      return fiber
    }
  }
}

class CanResume<A> : Fibre<A> {
  override var isDone = false
  private var res: A? = null
  override val result: A
    @Suppress("UNCHECKED_CAST")
    get() {
      check(isDone) { "This fiber is not yet done, can't get result" }
      return res as A
    }

  fun returnWith(value: A) {
    res = value
    isDone = true
  }

  private lateinit var k: suspend () -> Unit
  override suspend fun resume() {
    check(!isDone) { "Can't resume this fiber anymore" }
    k()
  }

  fun next(k: suspend () -> Unit) {
    this.k = k
  }
}

class CanSuspend<A>(prompt: HandlerPrompt<Unit>, private val fiber: CanResume<A>) : Handler<Unit> by prompt,
  Suspendable {
  override suspend fun suspend() = use { k ->
    fiber.next { k(Unit) }
  }
}

private fun makeTask(k: Cont<Boolean, Unit>): Task {
  val newK = k.copy()
  return { newK(true, shouldClear = true) }
}

class Scheduler2(prompt: HandlerPrompt<Unit>) : Handler<Unit> by prompt {
  private val tasks = ArrayDeque<Task>()
  suspend fun fork(): Boolean = use { k ->
    tasks.addLast(makeTask(k))
    // Kotlin compiler doesn't null the fields used for parameters,
    // hence the `shouldClear` is necessary to prevent memory leaks
    k(false, shouldClear = true)
  }

  suspend inline fun fork(task: Task) {
    if (fork()) {
      task()
      discardWith(Result.success(Unit))
    }
  }

  // Since we only run on one thread, we also need yield in the scheduler
  // to allow cooperative multitasking
  suspend fun yield() = use {
    tasks.addLast { it(Unit, shouldClear = true) }
  }

  // we can't run the scheduler in pure since the continuation that contains
  // the call to pure might be discarded by one fork, while another one
  // is still alive.
  suspend fun run() {
    while (tasks.isNotEmpty()) {
      tasks.removeFirst()()
    }
  }
}

suspend fun scheduler2(block: suspend Scheduler2.() -> Unit) {
  val s = Scheduler2(HandlerPrompt())
  s.prompt().handle { block(s) }
  // this is safe since all tasks container the scheduler prompt marker
  s.run()
}

interface Suspendable {
  suspend fun suspend()
}

typealias Task = suspend () -> Unit