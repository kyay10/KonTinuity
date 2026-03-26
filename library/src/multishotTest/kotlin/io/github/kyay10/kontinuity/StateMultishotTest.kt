package io.github.kyay10.kontinuity

import kotlin.test.Test

class StateMultishotTest {
  @Test
  fun ambientState1() = runTestCC {
    context(_: Amb, s: CrazyState)
    suspend fun ex1() {
      if (flip()) s.put(1)
    }
    ambList {
      myState(0) {
        ex1()
        read()
      }
    } shouldEq listOf(1, 0)
    myState(0) {
      ambList {
        ex1()
        read()
      }
    } shouldEq listOf(1, 1)
  }

  @Test
  fun ambientState2() = runTestCC {
    var isFirst = true
    myState(0) {
      read() shouldEq 0
      foo()
      read() shouldEq if (isFirst) 2 else 13
      isFirst = false
      put(42)
      read() shouldEq 42
    }
  }

  @Test
  fun ambientState3() = runTestCC {
    var first = true
    myState(0) {
      read() shouldEq 0
      bar()
      read() shouldEq if (first) 3 else 43
      put(42)
      read() shouldEq 42
      first = false
    }
  }

  @Test
  fun countDown8() = runTestCC {
    suspend fun Amb.countDown(
      s1: StateT<Int>,
      s2: StateT<Int>,
      s3: StateT<Int>,
      s4: StateT<Int>,
      s5: StateT<Int>,
      s6: StateT<Int>,
      s7: StateT<Int>,
      s8: StateT<Int>
    ): Boolean {
      while (s1.read() > 0) {
        s1.put(s1.read() - 1)
        s2.put(s2.read() - 1)
        s3.put(s3.read() - 1)
        s4.put(s4.read() - 1)
        s5.put(s5.read() - 1)
        s6.put(s6.read() - 1)
        s7.put(s7.read() - 1)
        s8.put(s8.read() - 1)
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
    res shouldEq listOf(true, false)
  }
}

interface CrazyState : StateT<Int> {
  suspend fun foo()
  suspend fun bar()
}

suspend fun <R> myState(init: Int, block: suspend CrazyState.() -> R): R = specialState(init) {
  handle {
    block(object : CrazyState, StateT<Int> by this@specialState {
      override suspend fun foo() {
        put(2)
        use { k ->
          val _ = k(Unit)
          put(13)
          k(Unit)
        }
      }

      override suspend fun bar() {
        put(2)
        use { k ->
          put(read() + 1)
          val _ = k(Unit)
          put(read() + 1)
          k(Unit)
        }
      }
    })
  }
}

suspend fun <R, S> specialState(init: S, block: suspend StateT<S>.() -> R): R = runState(init) {
  block(SpecialState(this))
}