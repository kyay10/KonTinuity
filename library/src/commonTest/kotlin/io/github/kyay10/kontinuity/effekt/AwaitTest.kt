package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runCC
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest as coroutinesRunTest

class AwaitTest {
  @Test
  fun example() = coroutinesRunTest {
    val printed = StringBuilder()
    runCC {
      mutableAwait {
        // TODO this doesn't work with virtual delay. Investigate
        //  Likely because of busy waiting in await
        //  This is also slightly flaky because it's running on a different dispatcher
        val d = async(Dispatchers.Default.limitedParallelism(1)) {
          printed.appendLine("started future")
          delay(100.milliseconds)
          42
        }
        // Yield call here to try and reduce flakiness. This desperately needs looking at!
        bridge { kotlinx.coroutines.yield() }
        if (fork()) {
          printed.appendLine("hello 1")
          yield()
          printed.appendLine("world 1")
          printed.appendLine(await(d) + 1)
        } else {
          printed.appendLine("hello 2")
          yield()
          printed.appendLine("world 2")
          yield()
          printed.appendLine("and it goes on 2")
          yield()
          printed.appendLine("and it goes on and on 2")
        }
      }
    }
    printed.toString() shouldBe """
      |started future
      |hello 1
      |hello 2
      |world 1
      |world 2
      |and it goes on 2
      |and it goes on and on 2
      |43
      |
    """.trimMargin()
  }
}

interface Await {
  suspend fun <A> MultishotScope.await(body: suspend MultishotScope.(suspend MultishotScope.(A) -> Unit) -> Unit): A
  suspend fun MultishotScope.fork(): Boolean
}

context(a: Await)
suspend fun MultishotScope.fork(): Boolean = with(a) { fork() }

context(a: Await)
suspend fun <A> MultishotScope.await(body: suspend MultishotScope.(suspend MultishotScope.(A) -> Unit) -> Unit): A =
  with(a) { await(body) }

context(_: Await)
tailrec suspend fun MultishotScope.forkN(n: Int): Int = when {
  n <= 1 -> 0
  fork() -> n - 1
  else -> forkN(n - 1)
}

context(_: Await)
suspend fun MultishotScope.exit(): Nothing = await { }

context(_: Await)
suspend fun MultishotScope.yield() = await { it(Unit) }

context(_: Await)
suspend fun <A> MultishotScope.await(d: Deferred<A>): A {
  do {
    yield()
    bridge { kotlinx.coroutines.yield() } // so that the deferred has a chance to run if we're single threaded
  } while (!d.isCompleted)
  return bridge { d.await() }
}

class MutableAwait(prompt: HandlerPrompt<Unit>, private val processes: MutableList<suspend MultishotScope.() -> Unit>) :
  Await, Handler<Unit> by prompt {
  override suspend fun <A> MultishotScope.await(body: suspend MultishotScope.(suspend MultishotScope.(A) -> Unit) -> Unit): A =
    use { k ->
    body {
      processes.add { k(it) }
      processes.removeFirst()()
    }
  }

  override suspend fun MultishotScope.fork(): Boolean = use { k ->
    processes.add { k(false) }
    k(true)
  }
}

suspend fun MultishotScope.mutableAwait(body: suspend context(MutableAwait) MultishotScope.() -> Unit) {
  val processes = mutableListOf<suspend MultishotScope.() -> Unit>()
  handle { body(MutableAwait(given<HandlerPrompt<Unit>>(), processes), this) }
  while (processes.isNotEmpty()) processes.removeFirst()(this)
}