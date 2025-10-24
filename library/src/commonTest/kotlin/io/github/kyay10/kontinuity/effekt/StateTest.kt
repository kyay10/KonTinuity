package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

class StateTest {
  @Test
  fun simpleRead() = runTestCC {
    stateFun(42) { get() } shouldBe 42
  }

  @Test
  fun countDown8() = runTestCC {
    context(_: MultishotScope<IR>)
    suspend fun <IR> Amb<IR>.countDown(
      s1: State<Int, IR>,
      s2: State<Int, IR>,
      s3: State<Int, IR>,
      s4: State<Int, IR>,
      s5: State<Int, IR>,
      s6: State<Int, IR>,
      s7: State<Int, IR>,
      s8: State<Int, IR>
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
    context(_: MultishotScope<IR>)
    suspend fun <IR> State<Int, IR>.countDown(): List<Int> {
      val printed = mutableListOf(Int.MIN_VALUE)
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
  fun silentCountDown() = runTestCC(timeout = 10.minutes) {
    context(_: MultishotScope<IR>)
    suspend fun <IR> State<Int, IR>.countDown() {
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
    context(_: MultishotScope<IR>)
    suspend fun <IR> Amb<IR>.ex1(s: CrazyState<IR>) {
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

interface CrazyState<in Region> : State<Int, Region> {
  context(_: MultishotScope<Region>)
  suspend fun foo()

  context(_: MultishotScope<Region>)
  suspend fun bar()
}

class MyState<R, in IR, OR>(prompt: StatefulPrompt<R, Data<Int>, IR, OR>) : CrazyState<IR>,
  SpecialState<R, Int, IR, OR>(prompt) {
  context(_: MultishotScope<IR>)
  override suspend fun foo() {
    put(2)
    use { k ->
      k(Unit)
      value.state = 13
      k(Unit)
    }
  }

  context(_: MultishotScope<IR>)
  override suspend fun bar() {
    put(2)
    use { k ->
      value.state++
      k(Unit)
      value.state++
      k(Unit)
    }
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, Region> myState(init: Int, block: suspend context(NewScope<Region>) CrazyState<NewRegion>.() -> R): R =
  handleStateful<_, SpecialState.Data<Int>, _>(SpecialState.Data(init)) {
    block(MyState(this))
  }

open class SpecialState<R, S, in IR, OR>(prompt: StatefulPrompt<R, Data<S>, IR, OR>) : State<S, IR>,
  StatefulHandler<R, SpecialState.Data<S>, IR, OR> by prompt {
  data class Data<S>(var state: S) : Stateful<Data<S>> {
    override fun fork() = copy()
  }

  context(_: MultishotScope<IR>)
  override suspend fun get(): S = value.state

  context(_: MultishotScope<IR>)
  override suspend fun put(value: S) {
    this.value.state = value
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, S, Region> specialState(init: S, block: suspend context(NewScope<Region>) State<S, NewRegion>.() -> R): R =
  handleStateful<_, SpecialState.Data<S>, _>(SpecialState.Data(init)) {
    block(SpecialState(this))
  }

class StateFun<R, S, in IR, OR>(p: HandlerPrompt<suspend context(MultishotScope<OR>) (S) -> R, IR, OR>) : State<S, IR>,
  Handler<suspend context(MultishotScope<OR>) (S) -> R, IR, OR> by p {
  context(_: MultishotScope<IR>)
  override suspend fun get(): S = useOnce { k ->
    { s ->
      k(s)(s)
    }
  }

  context(_: MultishotScope<IR>)
  override suspend fun put(value: S) = useOnce { k ->
    {
      k(Unit)(value)
    }
  }
}

context(_: MultishotScope<Region>)
suspend fun <R, S, Region> stateFun(init: S, block: suspend context(NewScope<Region>) State<S, NewRegion>.() -> R): R = handle {
  val res = block(StateFun(this))
  suspendOneArg { res }
}(init)

private fun <S, R, Region> suspendOneArg(block: suspend context(MultishotScope<Region>) (S) -> R): suspend context(MultishotScope<Region>) (S) -> R =
  block

interface State<S, in Region> {
  context(_: MultishotScope<Region>)
  suspend fun get(): S

  context(_: MultishotScope<Region>)
  suspend fun put(value: S)
}