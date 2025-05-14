/*
package io.github.kyay10.kontinuity.effekt.casestudies

import arrow.autoCloseScope
import io.github.kyay10.kontinuity.MultishotScope
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

interface AD<Num> {
  val Double.num: Num
  val Int.num: Num get() = toDouble().num
  suspend operator fun Num.plus(other: Num): Num
  suspend operator fun Num.times(other: Num): Num
  suspend fun exp(x: Num): Num
}

// d = 3 + 3x^2
suspend fun <Num> AD<Num>.prog(x: Num): Num = 3.num * x + x * x * x

// d = exp(1 + 2x) + 2x*exp(x^2)
suspend fun <Num> AD<Num>.progExp(x: Num): Num = 0.5.num * exp(1.num + 2.num * x) + exp(x * x)

data class NumF(val value: Double, val d: Double)

suspend fun MultishotScope.forwards(x: Double, prog: suspend context(AD<NumF>) MultishotScope.(NumF) -> NumF) = object : AD<NumF> {
  override val Double.num: NumF get() = NumF(this, 0.0)
  override suspend fun NumF.plus(other: NumF) = NumF(value + other.value, d + other.d)
  override suspend fun NumF.times(other: NumF) = NumF(value * other.value, value * other.d + d * other.value)
  override suspend fun exp(x: NumF) = NumF(mathExp(x.value), mathExp(x.value) * x.d)
}.prog(NumF(x, 1.0)).d

data class NumH<N>(val value: N, val d: N)

context(outer: AD<N>)
suspend fun <N> MultishotScope.forwardsHigher(x: N, prog: suspend (AD<NumH<N>>) MultishotScope.(NumH<N>) -> NumH<N>) = object : AD<NumH<N>> {
  override val Double.num: NumH<N> get() = NumH(this@num.num, 0.num)
  override suspend fun NumH<N>.plus(other: NumH<N>) = NumH(value + other.value, d + other.d)
  override suspend fun NumH<N>.times(other: NumH<N>) = NumH(value * other.value, d * other.value + other.d * value)
  override suspend fun exp(x: NumH<N>) =
    NumH(this@forwardsHigher.exp(x.value), this@forwardsHigher.exp(x.value) * x.d)
}.prog(NumH(x, 1.0.num)).d

suspend fun showString(prog: suspend AD<String>.(String) -> String) = object : AD<String> {
  override val Double.num: String get() = toString()
  @Suppress("EXTENSION_SHADOWED_BY_MEMBER")
  override suspend fun String.plus(other: String) = when {
    this == 0.0.toString() -> other
    other == 0.0.toString() -> this
    else -> "($this + $other)"
  }

  override suspend fun String.times(other: String) = when {
    this == 0.0.toString() || other == 0.0.toString() -> 0.0.toString()
    this == 1.0.toString() -> other
    else -> "($this * $other)"
  }

  override suspend fun exp(x: String) = "exp($x)"
}.prog("x")

data class NumB(val value: Double, var d: Double)

suspend fun backwards(x: Double, prog: suspend AD<NumB>.(NumB) -> NumB): Double {
  val input = NumB(x, 0.0)
  handle {
    val res = object : AD<NumB> {
      override val Double.num: NumB get() = NumB(this, 0.0)
      override suspend fun NumB.plus(other: NumB) = use { resume ->
        val z = NumB(value + other.value, 0.0)
        resume(z)
        d += z.d
        other.d += z.d
      }

      override suspend fun NumB.times(other: NumB) = use { resume ->
        val z = NumB(value * other.value, 0.0)
        resume(z)
        d += other.value * z.d
        other.d += value * z.d

      }

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

suspend fun backwardsAutoClose(x: Double, prog: suspend AD<NumB>.(NumB) -> NumB): Double {
  val input = NumB(x, 0.0)
  autoCloseScope {
    val res = object : AD<NumB> {
      override val Double.num: NumB get() = NumB(this, 0.0)
      override suspend fun NumB.plus(other: NumB) = NumB(value + other.value, 0.0).also { z ->
        onClose {
          this.d += z.d
          other.d += z.d
        }
      }

      override suspend fun NumB.times(other: NumB) = NumB(value * other.value, 0.0).also { z ->
        onClose {
          d += other.value * z.d
          other.d += value * z.d
        }
      }

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
}*/
