package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test

class StateTest {
  @Test
  fun simpleRead() = runTestCC {
    stateFun<Int, Int>(42) { get() } shouldBe 42
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
      while (s1.get() > 0) {
        s1.put(s1.get() - 1)
        s2.put(s2.get() - 1)
        s3.put(s3.get() - 1)
        s4.put(s4.get() - 1)
        s5.put(s5.get() - 1)
        s6.put(s6.get() - 1)
        s7.put(s7.get() - 1)
        s8.put(s8.get() - 1)
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

  @Test
  fun countDown() = runTestCC {
    suspend fun State<Int>.countDown(): List<Int> {
      val printed = mutableListOf<Int>(Int.MIN_VALUE)
      while (get() > 0) {
        printed.add(get())
        put(get() - 1)
      }
      printed.add(get())
      return printed
    }
    stateFun(10) {
      countDown()
    } shouldBe listOf(Int.MIN_VALUE, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
  }

  @Test
  fun silentCountDown() = runTestCC(UnconfinedTestDispatcher()) {
    suspend fun State<Int>.countDown() {
      while (get() > 0) {
        put(get() - 1)
      }
    }
    stateFun(10_000) {
      countDown()
    }
  }

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
        get()
      }
    } shouldBe listOf(1, 0)
    myState(0) {
      ambList {
        ex1(this@myState)
        get()
      }
    } shouldBe listOf(1, 1)
  }

  @Test
  fun ambientState2() = runTestCC {
    var isFirst = true
    myState(0) {
      get() shouldBe 0
      foo()
      get() shouldBe if (isFirst) 2 else 13
      isFirst = false
      put(42)
      get() shouldBe 42
    }
  }

  @Test
  fun ambientState3() = runTestCC {
    var first = true
    myState(0) {
      get() shouldBe 0
      bar()
      get() shouldBe if (first) 3 else 43
      put(42)
      get() shouldBe 42
      first = false
    }
  }
}

interface CrazyState : State<Int> {
  suspend fun foo()
  suspend fun bar()
}

class MyState<R>(prompt: StatefulPrompt<R, Data<Int>>) : CrazyState,
  SpecialState<R, Int>(prompt) {
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
      put(get() + 1)
      k(Unit)
      put(get() + 1)
      k(Unit)
    }
  }
}

suspend fun <R> myState(init: Int, block: suspend CrazyState.() -> R): R =
  handleStateful(SpecialState.Data(init)) {
    block(MyState<R>(this))
  }

open class SpecialState<R, S>(prompt: StatefulPrompt<R, Data<S>>) : State<S>,
  StatefulHandler<R, SpecialState.Data<S>> by prompt {
  data class Data<S>(var state: S) : Stateful<Data<S>> {
    override fun fork() = copy()
  }

  override suspend fun get(): S = (this as StatefulHandler<R, Data<S>>).get().state
  override suspend fun put(value: S) {
    (this as StatefulHandler<R, Data<S>>).get().state = value
  }
}

suspend fun <R, S> specialState(init: S, block: suspend State<S>.() -> R): R =
  handleStateful(SpecialState.Data(init)) {
    block(SpecialState(this))
  }

class StateFun<R, S>(p: HandlerPrompt<suspend (S) -> R>) : State<S>, Handler<suspend (S) -> R> by p {
  override suspend fun get(): S = useOnce { k ->
    { s ->
      k(s, shouldClear = true)(s)
    }
  }

  override suspend fun put(value: S) = useOnce { k ->
    {
      k(Unit, shouldClear = true)(value)
    }
  }
}

suspend fun <R, S> stateFun(init: S, block: suspend State<S>.() -> R): R = handle {
  val res = block(StateFun(this))
  suspendOneArg { res }
}(init)

private fun <S, R> suspendOneArg(block: suspend (S) -> R): suspend (S) -> R = block

interface State<S> {
  suspend fun get(): S
  suspend fun put(value: S)
}