package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runState
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StateMultishotTest {
  @Test
  fun ambientState1() = runTestCC {
    suspend fun Amb.ex1(s: CrazyState) {
      if (flip()) {
        s.put(1)
      }
    }
    ambList {
      myState(0) {
        ex1(this)
        getValue()
      }
    } shouldBe listOf(1, 0)
    myState(0) {
      ambList {
        ex1(this@myState)
        getValue()
      }
    } shouldBe listOf(1, 1)
  }

  @Test
  fun ambientState2() = runTestCC {
    var isFirst = true
    myState(0) {
      getValue() shouldBe 0
      foo()
      getValue() shouldBe if (isFirst) 2 else 13
      isFirst = false
      put(42)
      getValue() shouldBe 42
    }
  }

  @Test
  fun ambientState3() = runTestCC {
    var first = true
    myState(0) {
      getValue() shouldBe 0
      bar()
      getValue() shouldBe if (first) 3 else 43
      put(42)
      getValue() shouldBe 42
      first = false
    }
  }

  @Test
  fun countDown8() = runTestCC {
    suspend fun Amb.countDown(
      s1: State<Int>,
      s2: State<Int>,
      s3: State<Int>,
      s4: State<Int>,
      s5: State<Int>,
      s6: State<Int>,
      s7: State<Int>,
      s8: State<Int>
    ): Boolean {
      while (s1.getValue() > 0) {
        s1.put(s1.getValue() - 1)
        s2.put(s2.getValue() - 1)
        s3.put(s3.getValue() - 1)
        s4.put(s4.getValue() - 1)
        s5.put(s5.getValue() - 1)
        s6.put(s6.getValue() - 1)
        s7.put(s7.getValue() - 1)
        s8.put(s8.getValue() - 1)
      }
      return flip()
    }

    val init = 1_000
    val res = ambList {
      specialState(init) s1@{
        specialState(init) s2@{
          specialState(init) s3@{
            specialState(init) s4@{
              specialState(init) s5@{
                specialState(init) s6@{
                  specialState(init) s7@{
                    specialState(init) s8@{
                      countDown(this@s1, this@s2, this@s3, this@s4, this@s5, this@s6, this@s7, this@s8)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    res shouldBe listOf(true, false)
  }
}

interface CrazyState : State<Int> {
  suspend fun foo()
  suspend fun bar()
}

suspend fun <R> myState(init: Int, block: suspend CrazyState.() -> R): R = specialState(init) {
  handle {
    block(object : CrazyState, State<Int> by this@specialState {
      override suspend fun foo() {
        put(2)
        use { k ->
          k(Unit)
          put(13)
          k(Unit)
        }
      }

      override suspend fun bar() {
        put(2)
        use { k ->
          put(getValue() + 1)
          k(Unit)
          put(getValue() + 1)
          k(Unit)
        }
      }
    })
  }
}

suspend fun <R, S> specialState(init: S, block: suspend State<S>.() -> R): R = runState(init) {
  block(SpecialState(this))
}