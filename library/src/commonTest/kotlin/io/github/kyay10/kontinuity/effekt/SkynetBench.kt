package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.repeatIteratorless
import io.github.kyay10.kontinuity.runSuspendCC
import kotlinx.benchmark.*
import kotlinx.benchmark.State
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest as coroutinesRunTest

// skynet benchmark:
//    https://github.com/atemerev/skynet
@State(Scope.Benchmark)
open class SkynetBench {
  @Param("100000", "1000000")
  var N = 0

  @Benchmark
  fun skynetNoEffects(bh: Blackhole) = runSuspendCC {
    suspend fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val children = Array(div) {
        val subNum = num + it * (size / div)
        Fibre.create {
          skynet(subNum, size / div, div)
        }
      }
      return children.sumOf {
        it.resume()
        it.result
      }
    }

    val f = Fibre.create { skynet(0, N, 10) }
    f.resume()
    bh.consume(f.result)
  }

  @Benchmark
  fun skynetOverhead(bh: Blackhole) = runSuspendCC {
    suspend fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val children = Array<suspend () -> Long>(div) {
        val subNum = num + it * (size / div)
        { skynet(subNum, size / div, div) }
      }
      return children.sumOf { it() }
    }
    bh.consume(skynet(0, N, 10))
  }

  @Benchmark
  fun skynetNotEvenSuspend(bh: Blackhole) = runSuspendCC {
    fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      return (0..<div).sumOf {
        val subNum = num + it * (size / div)
        skynet(subNum, size / div, div)
      }
    }
    bh.consume(skynet(0, N, 10))
  }

  @Benchmark
  fun skynetFastScheduler(bh: Blackhole) = runSuspendCC {
    data class SkynetData(var sum: Long, var returned: Int)

    suspend fun Scheduler2.skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val data = SkynetData(0, 0)
      repeatIteratorless(div) {
        val subNum = num + it * (size / div)
        fastFork {
          val res = skynet(subNum, size / div, div)
          data.returned++
          data.sum += res
        }
      }
      while (data.returned < div) {
        yield()
      }
      yield()
      return data.sum
    }

    scheduler2 { bh.consume(skynet(0, N, 10)) }
  }

  @Benchmark
  fun skynetSuspend(bh: Blackhole) = runSuspendCC {
    suspend fun Suspendable.skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong().also { suspend() }
      val children = Array(div) {
        val subNum = num + it * (size / div)
        Fibre.create {
          skynet(subNum, size / div, div)
        }
      }
      children.forEach { it.resume() }
      return children.sumOf {
        it.resume()
        it.result
      }.also { suspend() }
    }

    val f = Fibre.create { skynet(0, N, 10) }
    f.resume()
    f.resume()
    bh.consume(f.result)
  }

  @Benchmark
  fun skynetCoroutinesBusy(bh: Blackhole) = coroutinesRunTest {
    data class SkynetData(var sum: Long, var returned: Int)

    suspend fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      val data = SkynetData(0, 0)
      repeatIteratorless(div) {
        val subNum = num + it * (size / div)
        launch {
          val res = skynet(subNum, size / div, div)
          data.returned++
          data.sum += res
        }
      }
      while (data.returned < div) {
        yield()
      }
      yield()
      return data.sum
    }

    bh.consume(skynet(0, N, 10))
  }

  @Benchmark
  fun skynetCoroutinesAwaitAll(bh: Blackhole) = coroutinesRunTest(timeout = 10.minutes) {
    suspend fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      return (0..<div).map {
        val subNum = num + it * (size / div)
        async {
          skynet(subNum, size / div, div)
        }
      }.awaitAll().sum()
    }

    bh.consume(skynet(0, N, 10))
  }

  @Benchmark
  fun skynetCoroutinesAwaitEach(bh: Blackhole) = coroutinesRunTest(timeout = 10.minutes) {
    suspend fun skynet(num: Int, size: Int, div: Int): Long {
      if (size <= 1) return num.toLong()
      return (0..<div).map {
        val subNum = num + it * (size / div)
        async {
          skynet(subNum, size / div, div)
        }
      }.sumOf { it.await() }
    }

    bh.consume(skynet(0, N, 10))
  }
}
