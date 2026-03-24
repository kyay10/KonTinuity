package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.repeatIteratorless
import io.github.kyay10.kontinuity.runSuspendCC
import kotlinx.benchmark.*
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class SkynetMultishotBench {
  @Param("100000", "1000000")
  var N = 0

  @Benchmark
  fun skynetScheduler(bh: Blackhole) = runSuspendCC {
    data class SkynetData(var sum: Long, var returned: Int)

    suspend fun Scheduler2.skynet(num: Int, size: Int, div: Int): Long {
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

    scheduler2 { bh.consume(skynet(0, N, 10)) }
  }

  @Benchmark
  fun skynetFlippedScheduler(bh: Blackhole) = runSuspendCC {
    data class SkynetData(var sum: Long, var returned: Int)

    suspend fun Scheduler2.skynet(num: Int, size: Int, div: Int): Long {
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

    scheduler2 { bh.consume(skynet(0, N, 10)) }
  }
}

suspend fun Scheduler2.fork(): Boolean = useWithFinal { k, final ->
  tasks.addLast { final(true) }
  k(false)
}

suspend inline fun Scheduler2.forkFlipped(task: Task) {
  if (!fork()) {
    task()
    discardWithFast(Result.success(Unit))
  }
}

suspend inline fun Scheduler2.fork(task: Task) {
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