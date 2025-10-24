package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.autoCloseScope
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.NewRegion
import io.github.kyay10.kontinuity.NewScope
import io.github.kyay10.kontinuity.effekt.handle
import io.github.kyay10.kontinuity.effekt.use
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.math.exp as mathExp

class AutomaticDifferentiationTest {
  @Test
  fun example() = runTestCC {
    forwards(2.0) { prog(it) } shouldBe 15.0
    backwards(2.0) { prog(it) } shouldBe 15.0
    backwardsAutoClose(2.0) { prog(it) } shouldBe 15.0

    forwards(3.0) { prog(it) } shouldBe 30.0
    backwards(3.0) { prog(it) } shouldBe 30.0
    backwardsAutoClose(3.0) { prog(it) } shouldBe 30.0

    forwards(0.0) { prog(it) } shouldBe 3.0
    backwards(0.0) { prog(it) } shouldBe 3.0
    backwardsAutoClose(0.0) { prog(it) } shouldBe 3.0


    forwards(1.0) { progExp(it) } shouldBe 25.522100580105757
    backwards(1.0) { progExp(it) } shouldBe 25.522100580105757
    backwardsAutoClose(1.0) { progExp(it) } shouldBe 25.522100580105757

    forwards(0.0) { progExp(it) } shouldBe mathExp(1.0)
    backwards(0.0) { progExp(it) } shouldBe mathExp(1.0)
    backwardsAutoClose(0.0) { progExp(it) } shouldBe mathExp(1.0)
    val one = 1.0

    showString { x ->
      forwardsHigher(x) { x ->
        forwardsHigher(x) { y ->
          forwardsHigher(y) { z -> prog(z) }
        }
      }
    } shouldBe "((($one + $one) + (($one + $one) * $one)) + (($one + $one) * $one))"


    // we have the same pertubation confusion as in Lantern
    forwards(1.0) { x ->
      val shouldBeOne = forwards(1.0) { y -> x + y }
      x * shouldBeOne.num
    } shouldBe 2.0

    backwards(1.0) { x ->
      val shouldBeOne = backwards(1.0) { y -> x + y }
      x * shouldBeOne.num
    } shouldBe 2.0

    backwardsAutoClose(1.0) { x ->
      val shouldBeOne = backwardsAutoClose(1.0) { y -> x + y }
      x * shouldBeOne.num
    } shouldBe 2.0

    // this is proposed by Wang et al. as a solution to pertubation confusion
    backwards(1.0) { x ->
      val shouldBeOne = forwards(1.0) { y -> x.value.num + y }
      x * shouldBeOne.num
    } shouldBe 1.0
    backwardsAutoClose(1.0) { x ->
      val shouldBeOne = forwards(1.0) { y -> x.value.num + y }
      x * shouldBeOne.num
    } shouldBe 1.0
  }
}

interface AD<Num, in Region> {
  val Double.num: Num
  val Int.num: Num get() = toDouble().num

  context(_: MultishotScope<Region>)
  suspend operator fun Num.plus(other: Num): Num

  context(_: MultishotScope<Region>)
  suspend operator fun Num.times(other: Num): Num

  context(_: MultishotScope<Region>)
  suspend fun exp(x: Num): Num
}

// d = 3 + 3x^2
context(_: MultishotScope<Region>)
suspend fun <Num, Region> AD<Num, Region>.prog(x: Num): Num = 3.num * x + x * x * x

// d = exp(1 + 2x) + 2x*exp(x^2)
context(_: MultishotScope<Region>)
suspend fun <Num, Region> AD<Num, Region>.progExp(x: Num): Num = 0.5.num * exp(1.num + 2.num * x) + exp(x * x)

data class NumF(val value: Double, val d: Double)

inline fun forwards(x: Double, prog: AD<NumF, Any?>.(NumF) -> NumF) = object : AD<NumF, Any?> {
  override val Double.num: NumF get() = NumF(this, 0.0)

  context(_: MultishotScope<Any?>)
  override suspend fun NumF.plus(other: NumF) = NumF(value + other.value, d + other.d)

  context(_: MultishotScope<Any?>)
  override suspend fun NumF.times(other: NumF) = NumF(value * other.value, value * other.d + d * other.value)

  context(_: MultishotScope<Any?>)
  override suspend fun exp(x: NumF) = NumF(mathExp(x.value), mathExp(x.value) * x.d)
}.prog(NumF(x, 1.0)).d

data class NumH<N>(val value: N, val d: N)

context(_: MultishotScope<Region>)
inline fun <N, Region> AD<N, Region>.forwardsHigher(x: N, prog: AD<NumH<N>, Region>.(NumH<N>) -> NumH<N>) =
  object : AD<NumH<N>, Region> {
    override val Double.num: NumH<N> get() = with(this@forwardsHigher) { NumH(this@num.num, 0.num) }

    context(_: MultishotScope<Region>)
    override suspend fun NumH<N>.plus(other: NumH<N>) = NumH(value + other.value, d + other.d)

    context(_: MultishotScope<Region>)
    override suspend fun NumH<N>.times(other: NumH<N>) = NumH(value * other.value, d * other.value + other.d * value)

    context(_: MultishotScope<Region>)
    override suspend fun exp(x: NumH<N>) =
      NumH(this@forwardsHigher.exp(x.value), this@forwardsHigher.exp(x.value) * x.d)
  }.prog(NumH(x, 1.0.num)).d

inline fun showString(prog: AD<String, Any?>.(String) -> String) = object : AD<String, Any?> {
  override val Double.num: String get() = toString()

  @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
  context(_: MultishotScope<Any?>)
  override suspend fun String.plus(other: String) = when {
    this == 0.0.toString() -> other
    other == 0.0.toString() -> this
    else -> "($this + $other)"
  }

  context(_: MultishotScope<Any?>)
  override suspend fun String.times(other: String) = when {
    this == 0.0.toString() || other == 0.0.toString() -> 0.0.toString()
    this == 1.0.toString() -> other
    else -> "($this * $other)"
  }

  context(_: MultishotScope<Any?>)
  override suspend fun exp(x: String) = "exp($x)"
}.prog("x")

data class NumB(val value: Double, var d: Double)

context(_: MultishotScope<Region>)
suspend fun <Region> backwards(x: Double, prog: suspend context(NewScope<Region>) AD<NumB, NewRegion>.(NumB) -> NumB): Double {
  val input = NumB(x, 0.0)
  handle {
    val res = object : AD<NumB, HandleRegion> {
      override val Double.num: NumB get() = NumB(this, 0.0)

      context(_: MultishotScope<HandleRegion>)
      override suspend fun NumB.plus(other: NumB) = use { resume ->
        val z = NumB(value + other.value, 0.0)
        resume(z)
        d += z.d
        other.d += z.d
      }

      context(_: MultishotScope<HandleRegion>)
      override suspend fun NumB.times(other: NumB) = use { resume ->
        val z = NumB(value * other.value, 0.0)
        resume(z)
        d += other.value * z.d
        other.d += value * z.d

      }

      context(_: MultishotScope<HandleRegion>)
      override suspend fun exp(x: NumB) = use { resume ->
        val xExp = mathExp(x.value)
        val z = NumB(xExp, 0.0)
        resume(z)
        x.d += xExp * z.d
      }
    }.prog(input)
    res.d += 1
  }
  return input.d
}

inline fun backwardsAutoClose(x: Double, prog: AD<NumB, Any?>.(NumB) -> NumB): Double {
  val input = NumB(x, 0.0)
  autoCloseScope {
    val res = object : AD<NumB, Any?> {
      override val Double.num: NumB get() = NumB(this, 0.0)

      context(_: MultishotScope<Any?>)
      override suspend fun NumB.plus(other: NumB) = NumB(value + other.value, 0.0).also { z ->
        onClose {
          this.d += z.d
          other.d += z.d
        }
      }

      context(_: MultishotScope<Any?>)
      override suspend fun NumB.times(other: NumB) = NumB(value * other.value, 0.0).also { z ->
        onClose {
          d += other.value * z.d
          other.d += value * z.d
        }
      }

      context(_: MultishotScope<Any?>)
      override suspend fun exp(x: NumB): NumB {
        val xExp = mathExp(x.value)
        val z = NumB(xExp, 0.0)
        onClose { x.d += xExp * z.d }
        return z
      }
    }.prog(input)
    res.d += 1
  }
  return input.d
}