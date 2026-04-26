package io.github.kyay10.kontinuity

import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest as coroutinesRunTest

class AwaitTest {
  @Test
  fun example() = coroutinesRunTest {
    val printed = StringBuilder()
    runCC {
      mutableAwait {
        // start it immediately
        val d =
          async(start = CoroutineStart.UNDISPATCHED) {
            printed.appendLine("started future")
            delay(100.milliseconds)
            42
          }
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
    printed.toString() shouldEq
      """
      |started future
      |hello 1
      |hello 2
      |world 1
      |world 2
      |and it goes on 2
      |and it goes on and on 2
      |43
      |"""
        .trimMargin()
  }
}

interface Await {
  suspend fun <A> await(body: suspend (suspend (A) -> Unit) -> Unit): A

  suspend fun fork(): Boolean
}

suspend fun Await.yield() = await { it(Unit) }

suspend fun <A> Await.await(d: Deferred<A>): A {
  do {
    yield()
    yield() // so that the deferred has a chance to run if we're single threaded
    delay(1.milliseconds)
  } while (!d.isCompleted)
  return d.await()
}

suspend fun mutableAwait(body: suspend Await.() -> Unit) =
  runQueue<suspend () -> Unit, _> {
    handle {
      body(
        object : Await {
          override suspend fun <A> await(body: suspend (suspend (A) -> Unit) -> Unit): A = use { k ->
            body {
              enqueue { k(it) }
              dequeue()()
            }
          }

          override suspend fun fork(): Boolean = use { k ->
            enqueue { k(false) }
            k(true)
          }
        }
      )
    }
    dequeueAll { it() }
  }
