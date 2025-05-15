package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.Reader
import io.github.kyay10.kontinuity.ask
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes


typealias LazyList<T> = LazyCons<T>?

data class LazyCons<out T>(
  val head: suspend MultishotScope.() -> T,
  val tail: suspend MultishotScope.() -> LazyList<T>
) : Shareable<LazyCons<T>> {
  context(_: Sharing)
  override fun shareArgs() = LazyCons(share(head), share(tail))
}

context(_: Amb, _: Exc)
private suspend fun <T> MultishotScope.perm(stream: Stream<T>?): Stream<T>? {
  stream ?: return null
  suspend fun MultishotScope.permuteRest() = perm(tail(stream))
  return insertStream(stream.value, MultishotScope::permuteRest)
}

context(amb: Amb, exc: Exc)
private suspend fun <T> MultishotScope.insertStream(x: T, mxs: (suspend MultishotScope.() -> Stream<T>?)?): Stream<T> =
  when {
  mxs == null || flip() -> Stream(x, mxs)
  else -> {
    val (y, mys) = mxs() ?: raise()
    suspend fun MultishotScope.insertRest() = insertStream(x, mys)
    Stream(y, MultishotScope::insertRest)
  }
}

class SharingTest {
  private tailrec suspend fun <T : Comparable<T>> MultishotScope.isSorted(list: LazyList<T>): Boolean {
    list ?: return true
    val (my, mys) = list.tail(this) ?: return true
    val x = list.head(this)
    val y = my()
    return if (x <= y) isSorted(LazyCons({ y }, mys)) else false
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> MultishotScope.perm(list: LazyList<T>): LazyList<T> {
    list ?: return null
    return insert(list.head) { perm(list.tail(this)) }
  }

  context(_: Sharing, _: Amb, _: Exc)
  private suspend fun <T : Comparable<T>> MultishotScope.sort(list: LazyList<T>): LazyList<T> {
    val permutation = share { perm(list) }
    ensure(isSorted(permutation()))
    return permutation()
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> MultishotScope.insert(
    mx: suspend MultishotScope.() -> T,
    mxs: suspend MultishotScope.() -> LazyList<T>
  ): LazyList<T> = when {
    flip() -> LazyCons(mx, mxs)
    else -> {
      val (my, mys) = mxs() ?: raise()
      LazyCons(my) { insert(mx, mys) }
    }
  }

  private suspend fun <T> MultishotScope.toPersistentList(list: LazyList<T>): PersistentList<T> {
    list ?: return persistentListOf()
    val x = list.head(this)
    return toPersistentList(list.tail(this)).add(0, x)
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
        toPersistentList(sort(list))
      }
    } shouldBe listOf(numbers)
  }

  private tailrec suspend fun <T : Comparable<T>> MultishotScope.isSorted(stream1: Stream<T>?): Boolean {
    stream1 ?: return true
    val stream2 = tail(stream1) ?: return true
    return if (stream1.value <= stream2.value) isSorted(stream2) else false
  }

  context(_: Sharing, _: Amb, _: Exc)
  private suspend fun <T : Comparable<T>> MultishotScope.sort(stream: Stream<T>?): Stream<T>? {
    val permutation = share { perm(stream) }
    ensure(isSorted(permutation()))
    return permutation()
  }

  private suspend fun <T> MultishotScope.toPersistentList(stream: Stream<T>?): PersistentList<T> {
    stream ?: return persistentListOf()
    return toPersistentList(tail(stream)).add(0, stream.value)
  }

  private fun <T> List<T>.toStream(): Stream<T>? = fold(null) { acc, i ->
    Stream(i, acc?.let { { acc } })
  }

  @Test
  fun streamSortingTest() = runTestCC(timeout = 10.minutes) {
    val numbers = (1..2).toList()
    val list = numbers.toStream()
    bagOfN {
      sharing {
        toPersistentList(sort(list))
      }
    } shouldBe listOf(numbers)
  }
}

context(r: Reader<MutableList<in Field<*>>?>)
private inline fun <A> memo(crossinline block: suspend MultishotScope.() -> A): suspend MultishotScope.() -> A {
  val key = Field<A>()
  return {
    key.getOrElse {
      block().also {
        key.set(it)
        r.ask()?.add(key)
      }
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
  fun <A> share(block: suspend MultishotScope.() -> A): suspend MultishotScope.() -> A
}

context(s: Sharing)
fun <A> share(block: suspend MultishotScope.() -> A): suspend MultishotScope.() -> A = s.share(block)

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

suspend fun <R> MultishotScope.sharing(block: suspend context(Sharing) MultishotScope.() -> R): R =
  runReader(null as MutableList<Field<*>>?, { mutableListOf() }) {
    block(object : Sharing {
      override fun <A> share(block: suspend MultishotScope.() -> A): suspend MultishotScope.() -> A =
        memo { block().shareArgs() }
    }, this).also {
      ask()?.forEach { it.clear() }
    }
  }
