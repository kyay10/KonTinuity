package io.github.kyay10.kontinuity.effekt
import kotlinx.benchmark.*
import kotlinx.benchmark.State

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 10, time = 10, timeUnit = BenchmarkTimeUnit.SECONDS)
@Measurement(iterations = 20, time = 10, timeUnit = BenchmarkTimeUnit.SECONDS)
@State(Scope.Benchmark)
open class SharingBench {
  // Parameterizes the benchmark to run with different list sizes
  @Param("15")
  var size: Int = 0
  // The actual benchmark method
  @Benchmark
  fun benchmarkMethod() {
    SharingTest().streamSortingTest()
  }
}