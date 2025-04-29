package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.random.Random
import kotlin.test.Test


typealias LazyList<T> = LazyCons<T>?

data class LazyCons<out T>(val head: suspend () -> T, val tail: suspend () -> LazyList<T>) : Shareable<LazyCons<T>> {
  context(_: Sharing)
  override suspend fun shareArgs() = LazyCons(share(head), share(tail))
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

sealed interface Thunk<out A>
data class Uneval<out A>(val thunk: suspend () -> A) : Thunk<A>
data class Eval<out A>(val value: A) : Thunk<A>

context(_: StateScope)
suspend fun <A> memo(block: suspend () -> A): suspend () -> A {
  val key = field<Thunk<A>>(Uneval(block))
  return {
    when (val thunk = key.get()) {
      is Eval -> thunk.value
      is Uneval -> thunk.thunk().also { key.set(Eval(it)) }
    }
  }
}

interface Sharing {
  suspend fun <A> share(block: suspend () -> A): suspend () -> A
}

context(s: Sharing)
suspend fun <A> share(block: suspend () -> A): suspend () -> A = s.share(block)

interface Shareable<out A : Shareable<A>> {
  context(_: Sharing)
  suspend fun shareArgs(): A
}

context(_: Sharing)
suspend fun <A> A.shareArgs(): A = if (this is Shareable<*>) {
  // Technically unsafe, but as long as all implementations use a self-type, we're fine
  @Suppress("UNCHECKED_CAST")
  shareArgs() as A
} else this

suspend fun <R> sharing(block: suspend context(Sharing) () -> R): R = persistentRegion {
  block(object : Sharing {
    override suspend fun <A> share(block: suspend () -> A): suspend () -> A = memo { block().shareArgs() }
  })
}