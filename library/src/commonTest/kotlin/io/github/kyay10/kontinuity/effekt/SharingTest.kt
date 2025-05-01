package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.Reader
import io.github.kyay10.kontinuity.runReader
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes


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
  fun sortingTest() = runTestCC(timeout = 10.minutes) {
    val numbers = (1..2).toList()
    val list = numbers.toLazyList()
    bagOfN {
      sharing {
        list.sort().toPersistentList()
      }
    } shouldBe listOf(numbers)
  }

  private tailrec suspend fun <T : Comparable<T>> Stream<T>?.isSorted(): Boolean {
    this ?: return true
    val stream = tail() ?: return true
    return if (value <= stream.value) stream.isSorted() else false
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> Stream<T>?.perm(): Stream<T>? {
    this ?: return null
    return insertStream(value) { tail()?.perm() }
  }

  context(_: Sharing, _: Amb, _: Exc)
  private suspend fun <T : Comparable<T>> Stream<T>?.sort(): Stream<T>? {
    val permutation = share { perm() }
    ensure(permutation().isSorted())
    return permutation()
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> insertStream(x: T, mxs: (suspend () -> Stream<T>?)?): Stream<T> = when {
    flip() -> Stream(x, mxs)
    else -> {
      val (y, mys) = mxs?.invoke() ?: raise()
      Stream(y) { insertStream(x, mys) }
    }
  }

  private suspend fun <T> Stream<T>?.toPersistentList(): PersistentList<T> {
    this ?: return persistentListOf()
    return tail().toPersistentList().add(0, value)
  }

  private fun <T> List<T>.toStream(): Stream<T>? = fold(null) { acc, i ->
    Stream(i) { acc }
  }

  @Test
  fun streamSortingTest() = runTestCC(timeout = 10.minutes) {
    val numbers = (1..2).toList()
    val list = numbers.toStream()
    bagOfN {
      sharing {
        list.sort().toPersistentList()
      }
    } shouldBe listOf(numbers)
  }
}

context(r: Reader<MutableList<Field<*>>?>)
private fun <A> memo(block: suspend () -> A): suspend () -> A = Memoized(r, Field(), block)::invoke

private class Memoized<A>(
  private val reader: Reader<MutableList<Field<*>>?>,
  private val key: Field<A>,
  private val block: suspend () -> A
) {
  suspend operator fun invoke(): A = key.getOrElse {
    block().also {
      key.set(it)
      reader.ask()?.add(key)
    }
  }
}

private class Field<T> {
  object EmptyValue

  @Suppress("UNCHECKED_CAST")
  private var value: T = EmptyValue as T
  fun set(value: T) {
    this.value = value
  }

  inline fun getOrElse(block: () -> T): T = when (val value = value) {
    EmptyValue -> block()
    else -> value
  }

  @Suppress("UNCHECKED_CAST")
  fun clear() {
    value = EmptyValue as T
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

suspend fun <R> sharing(block: suspend context(Sharing) () -> R): R =
  runReader(null as MutableList<Field<*>>?, { mutableListOf() }) {
    block(object : Sharing {
      override fun <A> share(block: suspend () -> A): suspend () -> A = memo { block().shareArgs() }
    }).also {
      ask()?.forEach { it.clear() }
    }
  }
