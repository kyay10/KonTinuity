package io.github.kyay10.kontinuity.effekt
import io.github.kyay10.kontinuity.runCC
import io.github.kyay10.kontinuity.runSuspend
import kotlinx.benchmark.*
import kotlinx.benchmark.State

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 20, time = 10, timeUnit = BenchmarkTimeUnit.SECONDS)
@State(Scope.Benchmark)
open class SharingBench {
  @Param("15")
  var size: Int = 0
  @Benchmark
  fun streamSharingBench() = runSuspend {
    runCC {
      val list = (1..size).toList().toStream()
      bagOfN {
        sharing {
          list.sort().toPersistentList()
        }
      }
    }
  }
}