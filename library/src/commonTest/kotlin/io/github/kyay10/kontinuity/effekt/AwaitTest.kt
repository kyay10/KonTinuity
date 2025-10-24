package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.bridge
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

interface Await<in IR, OR> {
  context(_: MultishotScope<IR>)
  suspend fun <A> await(body: suspend context(MultishotScope<OR>) (suspend context(MultishotScope<OR>) (A) -> Unit) -> Unit): A

  context(_: MultishotScope<IR>)
  suspend fun fork(): Boolean
}

context(_: MultishotScope<IR>)
tailrec suspend fun <IR> Await<IR, *>.forkN(n: Int): Int = when {
  n <= 1 -> 0
  fork() -> n - 1
  else -> forkN(n - 1)
}

context(_: MultishotScope<IR>)
suspend fun <IR> Await<IR, *>.exit(): Nothing = await { }

context(_: MultishotScope<IR>)
suspend fun <IR> Await<IR, *>.yield() = await { it(Unit) }

context(_: MultishotScope<IR>)
suspend fun <A, IR> Await<IR, *>.await(d: Deferred<A>): A {
  do {
    yield()
    bridge { kotlinx.coroutines.yield() } // so that the deferred has a chance to run if we're single threaded
  } while (!d.isCompleted)
  return bridge { d.await() }
}

class MutableAwait<in IR, OR>(
  prompt: HandlerPrompt<Unit, IR, OR>,
  private val processes: MutableList<suspend context(MultishotScope<OR>) () -> Unit>
) : Await<IR, OR>, Handler<Unit, IR, OR> by prompt {
  context(_: MultishotScope<IR>)
  override suspend fun <A> await(body: suspend context(MultishotScope<OR>) (suspend context(MultishotScope<OR>) (A) -> Unit) -> Unit): A =
    use { k ->
      body {
        processes.add { k(it) }
        processes.removeFirst()()
      }
    }

  context(_: MultishotScope<IR>)
  override suspend fun fork(): Boolean = use { k ->
    processes.add { k(false) }
    k(true)
  }
}

context(_: MultishotScope<Region>)
suspend fun <Region> mutableAwait(body: suspend context(NewScope<Region>) Await<NewRegion, Region>.() -> Unit) {
  val processes = mutableListOf<suspend context(MultishotScope<Region>) () -> Unit>()
  handle { body(MutableAwait(this, processes)) }
  while (processes.isNotEmpty()) processes.removeFirst()()
}