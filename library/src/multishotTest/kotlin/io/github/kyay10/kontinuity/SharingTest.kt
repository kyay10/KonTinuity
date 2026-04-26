package io.github.kyay10.kontinuity

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

typealias LazyList<T> = LazyCons<T>?

data class LazyCons<out T>(val head: Producer<T>, val tail: Producer<LazyList<T>>) : Shareable<LazyCons<T>> {
  context(_: Sharing)
  override fun shareArgs() = LazyCons(share(head), share(tail))

  companion object {
    inline operator fun <T> invoke(crossinline head: suspend () -> T, crossinline tail: suspend () -> LazyList<T>) =
      LazyCons(Producer(head), Producer(tail))
  }
}

context(_: Amb, _: Exc)
private suspend fun <T> Stream<T>?.perm(): Stream<T>? {
  val (x, xs) = this ?: return null
  return insertStream(x, Producer { xs?.invoke()?.perm() })
}

context(amb: Amb, exc: Exc)
private suspend fun <T> insertStream(x: T, mxs: Producer<Stream<T>?>?): Stream<T> =
  if (mxs == null || flip()) Stream(x, mxs)
  else {
    val (y, mys) = mxs().bind()
    Stream(y) { insertStream(x, mys) }
  }

private suspend fun Stream<Int>?.isSorted() = this == null || value.isSortedAux(tail())

private tailrec suspend fun Int.isSortedAux(rest: Stream<Int>?): Boolean =
  rest == null || this <= rest.value && rest.value.isSortedAux(rest.tail())

context(_: Sharing, _: Amb, _: Exc)
suspend fun Stream<Int>?.sort() = share { perm() }.also { ensure(it().isSorted()) }()

suspend fun <T> Stream<T>?.toPersistentList(): PersistentList<T> {
  this ?: return persistentListOf()
  return tail().toPersistentList().add(0, value)
}

fun <T> List<T>.toStream(): Stream<T>? = fold(null) { acc, i -> Stream(i, acc?.let(Producer.Companion::of)) }

context(_: Amb, _: Exc)
private suspend fun <T> insert(mx: Producer<T>, mxs: Producer<LazyList<T>>): LazyList<T> =
  if (flip()) LazyCons(mx, mxs)
  else {
    val (my, mys) = mxs().bind()
    LazyCons(my, Producer { insert(mx, mys) })
  }

private suspend fun LazyList<Int>.isSorted() = this == null || head.isSortedAux(tail())

private tailrec suspend fun Producer<Int>.isSortedAux(rest: LazyList<Int>): Boolean {
  val (my, mys) = rest ?: return true
  val x = this()
  val y = my()
  return x <= y && Producer.of(y).isSortedAux(mys())
}

context(_: Amb, _: Exc)
private suspend fun <T> LazyList<T>.perm(): LazyList<T> {
  val (x, xs) = this ?: return null
  return insert(x, Producer { xs().perm() })
}

context(_: Sharing, _: Amb, _: Exc)
suspend fun LazyList<Int>.sort(): LazyList<Int> {
  val permutation = share { perm() }
  ensure(permutation().isSorted())
  return permutation()
}

suspend fun <T> LazyList<T>.toPersistentList(): PersistentList<T> {
  this ?: return persistentListOf()
  val x = head()
  return tail().toPersistentList().add(0, x)
}

fun <T> List<T>.toLazyList(): LazyList<T> = fold(null) { acc, i -> LazyCons(Producer.of(i), Producer.of(acc)) }

class SharingTest {
  @Test
  fun sortingTest() =
    runTestCC(timeout = 10.minutes) {
      val numbers = (1..2).toList()
      bagOfN { sharing { numbers.toLazyList().sort().toPersistentList() } } shouldEq listOf(numbers)
    }

  @Test
  fun streamSortingTest() =
    runTestCC(timeout = 10.minutes) {
      val numbers = (1..2).toList()
      bagOfN { sharing { numbers.toStream().sort().toPersistentList() } } shouldEq listOf(numbers)
    }
}

private class Field<T> {
  object EmptyValue

  @Suppress("UNCHECKED_CAST") private var value: T = EmptyValue as T

  inline fun getOrPut(block: () -> T): T =
    when (val value = value) {
      EmptyValue -> block().also { this.value = it }
      else -> value
    }

  @Suppress("UNCHECKED_CAST")
  fun clear() {
    value = EmptyValue as T
  }
}

abstract class Producer<out A> {
  abstract suspend operator fun invoke(): A

  companion object {
    inline operator fun <A> invoke(crossinline block: suspend () -> A): Producer<A> =
      object : Producer<A>() {
        override suspend fun invoke(): A = block()
      }

    fun <A> of(a: A): Producer<A> = Producer { a }
  }
}

interface Sharing {
  fun <A> share(block: Producer<A>): Producer<A>
}

context(s: Sharing)
fun <A> share(block: Producer<A>): Producer<A> = s.share(block)

context(_: Sharing)
inline fun <A> share(crossinline block: suspend () -> A): Producer<A> = share(Producer(block))

interface Shareable<out A : Shareable<A>> {
  context(_: Sharing)
  fun shareArgs(): A
}

context(_: Sharing)
fun <A> A.shareArgs(): A =
  if (this is Shareable<*>) {
    // Technically unsafe, but as long as all implementations use a self-type, we're fine
    @Suppress("UNCHECKED_CAST")
    shareArgs() as A
  } else this

suspend fun <R> sharing(block: suspend context(Sharing) () -> R): R =
  runReader(null as MutableList<Field<*>>?, { ArrayList(20) }) {
    block(
        object : Sharing {
          override fun <A> share(block: Producer<A>): Producer<A> = memo { block().shareArgs() }
        }
      )
      .also { value?.forEach { it.clear() } }
  }

context(r: Reader<MutableList<in Field<*>>?>)
private inline fun <A> memo(crossinline block: suspend () -> A): Producer<A> {
  val key = Field<A>()
  return Producer { key.getOrPut { block().also { r.value?.add(key) } } }
}

suspend fun <R> sharingHonest(block: suspend context(Sharing) () -> R): R = intMapRegion {
  block(
    object : Sharing {
      override fun <A> share(block: Producer<A>): Producer<A> = memoHonest { block().shareArgs() }
    }
  )
}

context(_: Region)
private inline fun <A> memoHonest(crossinline block: suspend () -> A): Producer<A> {
  val key = field<A>()
  return Producer { key.getOrPut { block() } }
}
