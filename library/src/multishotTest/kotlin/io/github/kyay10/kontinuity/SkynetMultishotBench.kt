package io.github.kyay10.kontinuity

import kotlinx.benchmark.*
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class SkynetMultishotBench {
  @Param("100000", "1000000") var n = 0

  @Benchmark
  fun skynetScheduler(bh: Blackhole) = runSuspendCC {
    data class SkynetData(var sum: Long, var returned: Int)

    suspend fun SchedulerMultishot.skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val data = SkynetData(0, 0)
      repeatIteratorless(div) {
        val subNum = num + it * (size / div)
        fork {
          val res = skynet(subNum, size / div, div)
          data.returned++
          data.sum += res
        }
      }
      while (data.returned < div) yield()
      yield()
      return data.sum
    }

    schedulerMultishot { bh.consume(skynet(0, n, 10)) }
  }

  @Benchmark
  fun skynetFlippedScheduler(bh: Blackhole) = runSuspendCC {
    data class SkynetData(var sum: Long, var returned: Int)

    suspend fun SchedulerMultishot.skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val data = SkynetData(0, 0)
      repeatIteratorless(div) {
        val subNum = num + it * (size / div)
        forkFlipped {
          val res = skynet(subNum, size / div, div)
          data.returned++
          data.sum += res
        }
      }
      while (data.returned < div) yield()
      yield()
      return data.sum
    }

    schedulerMultishot { bh.consume(skynet(0, n, 10)) }
  }
}

interface SchedulerMultishot {
  suspend fun fork(): Boolean

  suspend fun exit(): Nothing

  suspend fun yield()
}

suspend fun schedulerMultishot(block: suspend SchedulerMultishot.() -> Unit) =
  runQueue<Task, _> {
    handle {
      block(
        object : SchedulerMultishot {
          override suspend fun exit(): Nothing = discardWithFast(Result.success(Unit))

          override suspend fun fork(): Boolean = use { k ->
            enqueue { k.final(true) }
            k(false)
          }

          override suspend fun yield() = useOnce { enqueue { it(Unit) } }
        }
      )
    }
    dequeueAll { it() }
  }

suspend inline fun SchedulerMultishot.forkFlipped(task: Task) {
  if (!fork()) {
    task()
    exit()
  }
}

suspend inline fun SchedulerMultishot.fork(task: Task) {
  // TODO this reveals an inefficiency in the SplitSeq code
  //  because here the frames up to the prompt are never used
  //  so we should never have to copy them, but seemingly we copy
  //  at least the SplitSeq elements by turning them into Segments
  //  so maybe we can delay segment creation?
  // Something something reflection without remorse?
  if (fork()) {
    task()
    exit()
  }
}
