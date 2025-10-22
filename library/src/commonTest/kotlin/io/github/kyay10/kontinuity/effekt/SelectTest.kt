package io.github.kyay10.kontinuity.effekt

import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlin.math.absoluteValue
import kotlin.test.Test

class SelectTest {
  @Test
  fun enumerateAll() = runTestCC {
    with(NQueens()) {
      val results = selectAll {
        nqueens(9)
      }
      results.size shouldBe 352
      tries shouldBe 8393 // y positions tried
    }
  }

  @Test
  fun backtracking() = runTestCC {
    with(NQueens()) {
      val results = selectFirst {
        nqueens(9)
      }
      results shouldBe Some(
        listOf(
          Pos(9, 5), Pos(8, 7), Pos(7, 9), Pos(6, 4), Pos(5, 2), Pos(4, 8), Pos(3, 6), Pos(2, 3), Pos(1, 1)
        )
      )
      tries shouldBe 41 // y positions tried
    }
  }
}

class NQueens {
  var tries = 0
  context(_: MultishotScope)
  suspend fun Select.nqueens(n: Int): List<Pos> {
    val ys = 1..n
    var queens = emptyList<Pos>()
    var x = 1
    while (x <= n) {
      queens = listOf(Pos(x, ys.available(x, queens).select())) + queens
      tries++
      x++
    }
    return queens
  }

  private fun IntRange.available(x: Int, queens: List<Pos>): Iterable<Int> = filter { y ->
    queens.all { !it.isAttacking(x, y) }
  }

  private fun Pos.isAttacking(x: Int, y: Int): Boolean =
    x == this.x || y == this.y || (x - this.x).absoluteValue == (y - this.y).absoluteValue
}

data class Pos(val x: Int, val y: Int)

interface Select {
  context(_: MultishotScope)
  suspend fun <A> Iterable<A>.select(): A
}

class SelectAll<R>(p: HandlerPrompt<List<R>>) : Select, Handler<List<R>> by p {
  context(_: MultishotScope)
  override suspend fun <A> Iterable<A>.select(): A = use { k ->
    fold(emptyList()) { acc, elem -> acc + k(elem) }
  }
}

context(_: MultishotScope)
suspend fun <R> selectAll(body: suspend context(MultishotScope) Select.() -> R): List<R> =
  handle { listOf(body(SelectAll(this))) }

class SelectFirst<R>(p: HandlerPrompt<Option<R>>) : Select, Handler<Option<R>> by p {
  context(_: MultishotScope)
  override suspend fun <A> Iterable<A>.select(): A = use { k ->
    forEach {
      val res = k(it)
      if (res is Some) return@use res
    }
    None
  }
}

context(_: MultishotScope)
suspend fun <R> selectFirst(body: suspend context(MultishotScope) Select.() -> R): Option<R> =
  handle { Some(body(SelectFirst(this))) }