package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.MultishotScope
import io.github.kyay10.kontinuity.Reader
import io.github.kyay10.kontinuity.runReader
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes


typealias LazyList<T, Region> = LazyCons<T, Region>?

data class LazyCons<out T, in Region>(
  val head: suspend context(MultishotScope<Region>) () -> T,
  val tail: suspend context(MultishotScope<Region>) () -> LazyList<T, Region>
) : Shareable<LazyCons<T, Region>> {
  context(_: Sharing)
  override fun shareArgs() = LazyCons(share(head), share(tail))
}

context(_: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
private suspend fun <T, Region> Stream<T, Region>?.perm(): Stream<T, Region>? {
  this ?: return null
  suspend fun MultishotScope<Region>.permuteRest() = tail()?.perm()
  return insertStream(value, MultishotScope<Region>::permuteRest)
}

context(amb: Amb<Region>, exc: Exc<Region>, _: MultishotScope<Region>)
private suspend fun <T, Region> insertStream(x: T, mxs: (suspend context(MultishotScope<Region>) () -> Stream<T, Region>?)?): Stream<T, Region> = when {
  mxs == null || flip() -> Stream(x, mxs)
  else -> {
    val (y, mys) = mxs() ?: raise()
    suspend fun MultishotScope<Region>.insertRest() = insertStream(x, mys)
    Stream(y, MultishotScope<Region>::insertRest)
  }
}

context(_: MultishotScope<Region>)
private tailrec suspend fun <T : Comparable<T>, Region> Stream<T, Region>?.isSorted(): Boolean {
  this ?: return true
  val stream = tail() ?: return true
  return if (value <= stream.value) stream.isSorted() else false
}

context(_: Sharing, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
suspend fun <T : Comparable<T>, Region> Stream<T, Region>?.sort(): Stream<T, Region>? {
  val permutation = share { perm() }
  ensure(permutation().isSorted())
  return permutation()
}

context(_: MultishotScope<Region>)
suspend fun <T, Region> Stream<T, Region>?.toPersistentList(): PersistentList<T> {
  this ?: return persistentListOf()
  return tail().toPersistentList().add(0, value)
}

fun <T> List<T>.toStream(): Stream<T, Any?>? = fold(null) { acc, i ->
  Stream(i, acc?.let { { acc } })
}

class SharingTest {
  context(_: MultishotScope<Region>)
  private tailrec suspend fun <T : Comparable<T>, Region> LazyList<T, Region>.isSorted(): Boolean {
    this ?: return true
    val (my, mys) = tail() ?: return true
    val x = head()
    val y = my()
    return if (x <= y) LazyCons({ y }, mys).isSorted() else false
  }

  context(_: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
  private suspend fun <T, Region> LazyList<T, Region>.perm(): LazyList<T, Region> {
    this ?: return null
    return insert(head) { tail().perm() }
  }

  context(_: Sharing, _: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
  private suspend fun <T : Comparable<T>, Region> LazyList<T, Region>.sort(): LazyList<T, Region> {
    val permutation = share { perm() }
    ensure(permutation().isSorted())
    return permutation()
  }

  context(_: Amb<Region>, _: Exc<Region>, _: MultishotScope<Region>)
  private suspend fun <T, Region> insert(
    mx: suspend context(MultishotScope<Region>) () -> T,
    mxs: suspend context(MultishotScope<Region>) () -> LazyList<T, Region>
  ): LazyList<T, Region> = when {
    flip() -> LazyCons(mx, mxs)
    else -> {
      val (my, mys) = mxs() ?: raise()
      LazyCons(my) { insert(mx, mys) }
    }
  }

  context(_: MultishotScope<Region>)
  private suspend fun <T, Region> LazyList<T, Region>.toPersistentList(): PersistentList<T> {
    this ?: return persistentListOf()
    val x = head()
    return tail().toPersistentList().add(0, x)
  }

  private fun <T> List<T>.toLazyList(): LazyList<T, Any?> = fold(null) { acc, i ->
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

context(r: Reader<MutableList<in Field<*>>?>)
private inline fun <A, Region> memo(crossinline block: suspend context(MultishotScope<Region>) () -> A): suspend context(MultishotScope<Region>) () -> A {
  val key = Field<A>()
  return Invokable<_, Region> {
    key.getOrElse {
      block().also {
        key.set(it)
        r.ask()?.add(key)
      }
    }
  }.invoker()
}

fun <A, Region> Invokable<A, Region>.invoker(): suspend context(MultishotScope<Region>) () -> A = { invoke() }
fun interface Invokable<A, in Region> {
  context(_: MultishotScope<Region>)
  suspend fun invoke(): A
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
  fun <A, Region> share(block: suspend context(MultishotScope<Region>) () -> A): suspend context(MultishotScope<Region>) () -> A
}

context(s: Sharing)
fun <A, Region> share(block: suspend context(MultishotScope<Region>) () -> A): suspend context(MultishotScope<Region>) () -> A = s.share(block)

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

context(_: MultishotScope<Region>)
suspend fun <R, Region> sharing(block: suspend context(Sharing, MultishotScope<Region>) () -> R): R =
  runReader(null as MutableList<Field<*>>?, { mutableListOf() }) {
    context(object : Sharing {
      override fun <A, Region> share(block: suspend context(MultishotScope<Region>) () -> A): suspend context(MultishotScope<Region>) () -> A =
        memo { block().shareArgs() }
    }) {
      block().also {
        ask()?.forEach { it.clear() }
      }
    }
  }
