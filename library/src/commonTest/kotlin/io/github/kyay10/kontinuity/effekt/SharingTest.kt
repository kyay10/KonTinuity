package io.github.kyay10.kontinuity.effekt

import arrow.core.getOrElse
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.random.Random
import kotlin.test.Test


typealias LazyList<T> = LazyCons<T>?

data class LazyCons<out T>(val head: suspend () -> T, val tail: suspend () -> LazyList<T>) : Shareable<LazyCons<T>> {
  context(_: Sharing)
  override fun shareArgs() = LazyCons(share(head), share(tail))
}

class SharingTest {
  private tailrec suspend fun <T : Comparable<T>> LazyList<T>.isSorted(): Boolean {
    this ?: return true
    val (my, mys) = tail() ?: return true
    val x = head()
    val y = my()
    return if (x <= y) LazyCons({ y }, mys).isSorted() else false
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> LazyList<T>.perm(): LazyList<T> {
    this ?: return null
    return insert(head) { tail().perm() }
  }

  context(_: Sharing, _: Amb, _: Exc)
  private suspend fun <T : Comparable<T>> LazyList<T>.sort(): LazyList<T> {
    val permutation = share { perm() }
    ensure(permutation().isSorted())
    return permutation()
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> insert(mx: suspend () -> T, mxs: suspend () -> LazyList<T>): LazyList<T> = when {
    flip() -> LazyCons(mx, mxs)
    else -> {
      val (my, mys) = mxs() ?: raise()
      LazyCons(my) { insert(mx, mys) }
    }
  }

  private suspend fun <T> LazyList<T>.toPersistentList(): PersistentList<T> {
    this ?: return persistentListOf()
    val x = head()
    return tail().toPersistentList().add(0, x)
  }

  private fun <T> List<T>.toLazyList(): LazyList<T> = fold(null) { acc, i ->
    LazyCons({ i }) { acc }
  }

  @Test
  fun sortingTest() = runTestCC {
    val numbers = (1..20).toList()
    val list = numbers.shuffled(Random(123456789)).toLazyList()
    onceOrNull {
      sharing {
        list.sort().toPersistentList()
      }
    } shouldBe numbers
  }
}

context(_: StateScope)
fun <A> memo(block: suspend () -> A): suspend () -> A = Memoized(field(), block)

private class Memoized<A>(
  private val key: StateScope.OptionalField<A>,
  private val block: suspend () -> A
) : suspend () -> A {
  override suspend fun invoke(): A = key.getOrNone().getOrElse {
    val value = block()
    key.set(value)
    value
  }
}

interface Sharing {
  fun <A> share(block: suspend () -> A): suspend () -> A
}

context(s: Sharing)
fun <A> share(block: suspend () -> A): suspend () -> A = s.share(block)

interface Shareable<out A : Shareable<A>> {
  context(_: Sharing)
  fun shareArgs(): A
}

context(_: Sharing)
fun <A> A.shareArgs(): A = if (this is Shareable<*>) {
  // Technically unsafe, but as long as all implementations use a self-type, we're fine
  @Suppress("UNCHECKED_CAST")
  shareArgs() as A
} else this

suspend fun <R> sharing(block: suspend context(Sharing) () -> R): R = persistentRegion {
  block(object : Sharing {
    override fun <A> share(block: suspend () -> A): suspend () -> A =
      if (block is Memoized<*>) block else memo { block().shareArgs() }
  })
}