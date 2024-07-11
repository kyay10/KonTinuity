package effekt

import io.kotest.matchers.shouldBe
import pushReader
import runTestCC
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

    val init = 100 // TODO fix OOM then increase to 1_000
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
  fun silentCountDown() = runTestCC {
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
    myState(0) {
      get() shouldBe 0
      foo()
      get() shouldBe 2
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

class MyState<R>(prompt: HandlerPrompt<Pair<R, Int>>) : CrazyState, SpecialState<Pair<R, Int>, Int>,
  Handler<Pair<R, Int>> by prompt {
  override suspend fun foo() {
    put(2)
    use { k ->
      k(Unit)
      pushReader(13) { // doesn't affect the continuation call since the state will be restored.
        k(Unit)
      }
    }
  }

  override suspend fun bar() {
    put(2)
    useStateful { k, s ->
      val (_, s) = k(Unit, s + 1)
      k(Unit, s + 1)
    }
  }
}

suspend fun <R> myState(init: Int, block: suspend CrazyState.() -> R): R =
  with(MyState<R>(HandlerPrompt())) {
    handleStateful(init) {
      val res = block()
      res to get()
    }.first
  }

fun interface SpecialState<R, S> : State<S>, StatefulHandler<R, S> {
  override suspend fun get(): S = (this as Reader<S>).get()

  override suspend fun put(value: S) = (this as Reader<S>).set(value)
}

suspend fun <R, S> specialState(init: S, block: suspend State<S>.() -> R): R =
  handleStateful(::SpecialState, init, block)

fun interface StateFun<R, S> : State<S>, Handler<suspend (S) -> R> {
  override suspend fun get(): S = use { k ->
    { s ->
      k(s)(s)
    }
  }

  override suspend fun put(value: S) = use { k ->
    {
      k(Unit)(value)
    }
  }
}

suspend fun <R, S> stateFun(init: S, block: suspend State<S>.() -> R): R = handle<suspend (S) -> R, _>(::StateFun) {
  val res = block()
  suspendOneArg { res }
}(init)

private fun <S, R> suspendOneArg(block: suspend (S) -> R): suspend (S) -> R = block

interface State<S> {
  suspend fun get(): S
  suspend fun put(value: S)
}