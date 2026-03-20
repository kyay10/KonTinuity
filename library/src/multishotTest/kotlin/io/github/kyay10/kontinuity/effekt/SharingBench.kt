package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runSuspendCC
import kotlinx.benchmark.*
import kotlinx.benchmark.State

@State(Scope.Benchmark)
open class SharingBench {
  @Param("15", "16", "17", "18")
  var size: Int = 0

  @Benchmark
  fun streamSharingBench(bh: Blackhole) = runSuspendCC {
    val list = (1..size).toList().toStream()
    bagOfN {
      sharing {
        list.sort().toPersistentList()
      }
    }.let(bh::consume)
  }
}