package io.github.kyay10.kontinuity

import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.left
import arrow.core.right
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FcontrolJvmTest {
  @Test
  fun tooBig() = runTest {
    data class TooBig(val value: Int)

    suspend fun Prompt<TooBig>.ex2(m: Int) = if (m > 5) abort(TooBig(m)) else m
    suspend fun Prompt<TooBig>.exRec(body: suspend () -> Int): Int = newReset {
      val m = this@exRec.reset {
        abort(body())
      }
      if (m.value <= 7) m.value else abort(m)
    }

    runCC {
      val res = newReset {
        newReset<TooBig> {
          abort(runList {
            ex2(listOf(5, 7, 1).bind())
          }.right())
        }.left()
      }
      res shouldBe Left(TooBig(7))
    }

    runCC {
      val res = newReset {
        newReset<TooBig> {
          abort(runList {
            exRec {
              ex2(listOf(5, 7, 1).bind())
            }
          }.right())
        }.left()
      }
      res shouldBe Right(listOf(5, 7, 1))
    }

    runCC {
      val res = newReset {
        newReset<TooBig> {
          abort(runList {
            exRec {
              ex2(listOf(5, 7, 11, 1).bind())
            }
          }.right())
        }.left()
      }
      res shouldBe Left(TooBig(11))
    }
  }

  @Test
  fun tooBigHandler() = runTest {
    data class TooBig(val value: Int)

    suspend fun Fcontrol<TooBig, Nothing>.ex2(m: Int) = if (m > 5) fcontrol(TooBig(m)) else m
    suspend fun Fcontrol<TooBig, Nothing>.exRec(body: suspend Fcontrol<TooBig, Nothing>.() -> Int): Int =
      newResetFcontrol({ error, _ ->
        if (error.value <= 7) error.value else this@exRec.fcontrol(error)
      }, body)

    runCC {
      val res = newResetFcontrol({ error: TooBig, _ -> error.left() }) {
        runList {
          ex2(listOf(5, 7, 1).bind())
        }.right()
      }
      res shouldBe Left(TooBig(7))
    }

    runCC {
      val res = newResetFcontrol({ error: TooBig, _ -> error.left() }) {
        runList {
          exRec {
            ex2(listOf(5, 7, 1).bind())
          }
        }.right()
      }
      res shouldBe Right(listOf(5, 7, 1))
    }

    runCC {
      val res = newResetFcontrol({ error: TooBig, _ -> error.left() }) {
        runList {
          exRec {
            ex2(listOf(5, 7, 11, 1).bind())
          }
        }.right()
      }
      res shouldBe Left(TooBig(11))
    }
  }
}