package io.github.kyay10.kontinuity

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class ReaderTest {
  @Test
  fun simple() = runTestCC {
    runReader(1) {
      newReset {
        pushReader(2) {
          shift { f -> ask() }
        }
      }
    } shouldBe 1
  }

  sealed class R<in A, out B> {
    data class R<out B>(val b: B) : ReaderTest.R<Any?, B>()
    data class J<in A, out B>(val f: Cont<A, ReaderTest.R<A, B>>) : ReaderTest.R<A, B>()
  }

  // https://www.brinckerhoff.org/clements/csc530-sp08/Readings/kiselyov-2006.pdf
  @Test
  fun example6FromDBDCPaper() = runTestCC {
    val p = Reader<Int>()
    val r = Reader<Int>()
    val f = p.pushReader(1) {
      newReset<R<Int, Int>> {
        r.pushReader(10) {
          shift {
            p.ask() shouldBe 1
            R.J(it)
          } shouldBe 0
          R.R(p.ask() + r.ask())
        }
      }
    }.shouldBeInstanceOf<R.J<Int, Int>>()
    p.pushReader(2) {
      r.pushReader(20) {
        f.f(0)
      }
    }.shouldBeInstanceOf<R.R<Int>>().b shouldBe 12
  }
}