package io.github.kyay10.kontinuity.effekt

import io.github.kyay10.kontinuity.Reader
import io.github.kyay10.kontinuity.runReader
import io.github.kyay10.kontinuity.runTestCC
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTimedValue


typealias LazyList<T> = LazyCons<T>?

data class LazyCons<out T>(val head: Producer<T>, val tail: Producer<LazyList<T>>) : Shareable<LazyCons<T>> {
  context(_: Sharing)
  override fun shareArgs() = LazyCons(share(head), share(tail))
}

context(_: Amb, _: Exc)
private suspend fun <T> Stream<T>?.perm(): Stream<T>? {
  this ?: return null
  val next = next
  return insertStream(value, Producer { next?.invoke()?.perm() })
}

context(amb: Amb, exc: Exc)
private suspend fun <T> insertStream(x: T, mxs: Producer<Stream<T>?>?): Stream<T> = when {
  mxs == null || flip() -> Stream(x, mxs)
  else -> {
    val (y, mys) = mxs() ?: raise()
    Stream(y, Producer { insertStream(x, mys) })
  }
}

private tailrec suspend fun <T : Comparable<T>> Stream<T>?.isSorted(): Boolean {
  this ?: return true
  val stream = tail() ?: return true
  return if (value <= stream.value) stream.isSorted() else false
}

context(_: Sharing, _: Amb, _: Exc)
suspend fun <T : Comparable<T>> Stream<T>?.sort(): Stream<T>? {
  val permutation = share(Producer { perm() })
  ensure(permutation().isSorted())
  return permutation()
}

suspend fun <T> Stream<T>?.toPersistentList(): PersistentList<T> {
  this ?: return persistentListOf()
  return tail().toPersistentList().add(0, value)
}

fun <T> List<T>.toStream(): Stream<T>? = fold(null) { acc, i ->
  Stream(i, acc?.let(Producer.Companion::of))
}

class SharingTest {
  private tailrec suspend fun <T : Comparable<T>> LazyList<T>.isSorted(): Boolean {
    this ?: return true
    val (my, mys) = tail() ?: return true
    val x = head()
    val y = my()
    return if (x <= y) LazyCons(Producer.of(y), mys).isSorted() else false
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> LazyList<T>.perm(): LazyList<T> {
    this ?: return null
    return insert(head, Producer { tail().perm() })
  }

  context(_: Sharing, _: Amb, _: Exc)
  private suspend fun <T : Comparable<T>> LazyList<T>.sort(): LazyList<T> {
    val permutation = share(Producer { perm() })
    ensure(permutation().isSorted())
    return permutation()
  }

  context(_: Amb, _: Exc)
  private suspend fun <T> insert(mx: Producer<T>, mxs: Producer<LazyList<T>>): LazyList<T> = when {
    flip() -> LazyCons(mx, mxs)
    else -> {
      val (my, mys) = mxs() ?: raise()
      LazyCons(my, Producer { insert(mx, mys) })
    }
  }

  private suspend fun <T> LazyList<T>.toPersistentList(): PersistentList<T> {
    this ?: return persistentListOf()
    val x = head()
    return tail().toPersistentList().add(0, x)
  }

  private fun <T> List<T>.toLazyList(): LazyList<T> = fold(null) { acc, i ->
    LazyCons(Producer.of(i), Producer.of(acc))
  }

  @Test
  fun sortingTest() = runTestCC(timeout = 10.minutes) {
    val numbers = (1..2).toList()
    val (result, time) = measureTimedValue {
      bagOfN {
        sharing {
          numbers.toLazyList().sort().toPersistentList()
        }
      }
    }
    println(time)
    result shouldBe listOf(numbers)
  }

  @Test
  fun streamSortingTest() = runTestCC(timeout = 10.minutes) {
    val numbers = (1..2).toList()
    val (result, time) = measureTimedValue {
      bagOfN {
        sharing {
          numbers.toStream().sort().toPersistentList()
        }
      }
    }
    println(time)
    result shouldBe listOf(numbers)
  }
}

context(r: Reader<out MutableList<in Field<*>>?>)
private inline fun <A> memo(crossinline block: suspend () -> A): Producer<A> {
  val key = Field<A>()
  return Producer {
    key.getOrPut { block().also { r.value?.add(key) } }
  }
}

private class Field<T> {
  object EmptyValue

  @Suppress("UNCHECKED_CAST")
  private var value: T = EmptyValue as T

  inline fun getOrPut(block: () -> T): T = when (val value = value) {
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
    inline operator fun <A> invoke(crossinline block: suspend () -> A): Producer<A> = object : Producer<A>() {
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
  runReader(null as MutableList<Field<*>>?, { ArrayList(20) }) {
    block(object : Sharing {
      override fun <A> share(block: Producer<A>): Producer<A> = memo { block().shareArgs() }
    }).also {
      value?.forEach { it.clear() }
    }
  }
