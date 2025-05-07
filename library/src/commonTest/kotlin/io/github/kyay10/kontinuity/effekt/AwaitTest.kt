package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runCC
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest as coroutinesRunTest
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

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
        kotlinx.coroutines.yield()
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
  suspend fun <A> await(body: suspend (suspend (A) -> Unit) -> Unit): A
  suspend fun fork(): Boolean
}

tailrec suspend fun Await.forkN(n: Int): Int = when {
  n <= 1 -> 0
  fork() -> n - 1
  else -> forkN(n - 1)
}

suspend fun Await.exit(): Nothing = await { }
suspend fun Await.yield() = await { it(Unit) }
suspend fun <A> Await.await(d: Deferred<A>): A {
  do {
    yield()
    kotlinx.coroutines.yield() // so that the deferred has a chance to run if we're single threaded
  } while (!d.isCompleted)
  return d.await()
}

class MutableAwait(prompt: HandlerPrompt<Unit>, private val processes: MutableList<suspend () -> Unit>) : Await, Handler<Unit> by prompt {
  override suspend fun <A> await(body: suspend (suspend (A) -> Unit) -> Unit): A = use { k ->
    body {
      processes.add { k(it) }
      processes.removeFirst()()
    }
  }

  override suspend fun fork(): Boolean = use { k ->
    processes.add { k(false) }
    k(true)
  }
}

suspend fun mutableAwait(body: suspend MutableAwait.() -> Unit) {
  val processes = mutableListOf<suspend () -> Unit>()
  handle { body(MutableAwait(this, processes)) }
  while (processes.isNotEmpty()) processes.removeFirst()()
}