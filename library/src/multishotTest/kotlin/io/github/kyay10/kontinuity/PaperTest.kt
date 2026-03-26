package io.github.kyay10.kontinuity

import arrow.core.None
import arrow.core.Some
import kotlin.test.Test

class PaperTest {
  context(_: Amb, _: Exc)
  suspend fun drunkFlip(): String {
    ensure(flip()) // check we caught the coin
    return if (flip()) "Heads" else "Tails"
  }

  @Test
  fun ex4dot1() = runTestCC {
    ambList {
      maybe {
        drunkFlip()
      }
    } shouldEq listOf(Some("Heads"), Some("Tails"), None)
    maybe {
      ambList {
        drunkFlip()
      }
    } shouldEq None
  }

  @Suppress("UnusedLambdaExpression")
  @Test
  fun ex4dot3() = runTestCC {
    ambList {
      var x = 0
      { x } // we force x to be captured in a closure
      if (flip()) x = 2
      x
    } shouldEq listOf(2, 2)
    ambList {
      var x = 0
      if (flip()) x = 2
      x
    } shouldEq listOf(2, 0)

    ambList {
      region {
        var x by field(0)
        if (flip()) x = 2
        x
      }
    } shouldEq listOf(2, 0)
    region {
      ambList {
        var x by field(0)
        if (flip()) x = 2
        x
      }
    } shouldEq listOf(2, 2)
  }

  context(_: SchedulerMultishot, _: Async)
  suspend fun asyncExample() {
    val str = buildString {
      val p = async {
        appendLine("Async 1")
        yield()
        appendLine("Async 2")
        yield()
        42
      }
      appendLine("Main")
      val r = p.await()
      appendLine("Main with result $r")
    }
    str shouldEq """
      Async 1
      Main
      Async 2
      Main with result 42
      
    """.trimIndent()
  }

  @Test
  fun ex4dot5dot4() = runTestCC {
    backtrack { drunkFlip() } shouldEq Some("Heads")
  }

  @Test
  fun ex4dot5dot6() = runTestCC {
    listRegion {
      fiber {
        poll {
          asyncExample()
        }
      }
    }
  }
}

context(fiber: SchedulerMultishot)
suspend fun yield() = fiber.yield()

interface Async {
  suspend fun <T> async(body: suspend () -> T): Promise<T>
  fun interface Promise<T> {
    suspend fun await(): T
  }
}

context(async: Async)
suspend fun <T> async(body: suspend () -> T): Async.Promise<T> = async.async(body)

context(_: Region)
suspend fun SchedulerMultishot.poll(block: suspend Async.() -> Unit) = block(object : Async {
  override suspend fun <T> async(body: suspend () -> T): Async.Promise<T> {
    val p = field<T>()
    fork { p.value = body() }
    return object : Async.Promise<T> {
      override tailrec suspend fun await(): T = p.getOrPut {
        yield()
        return await()
      }
    }
  }
})

suspend fun fiber(block: suspend SchedulerMultishot.() -> Unit) = runQueue<suspend () -> Unit, Unit> {
  handle {
    block(object : SchedulerMultishot {
      override suspend fun exit(): Nothing = discard {}
      override suspend fun fork(): Boolean = use { resume ->
        push { resume(false) }
        push { resume(true) }
        while (isNotEmpty()) dequeue()()
      }

      override suspend fun yield() = use { resume ->
        enqueue { resume(Unit) }
        while (isNotEmpty()) dequeue()()
      }
    })
  }
}