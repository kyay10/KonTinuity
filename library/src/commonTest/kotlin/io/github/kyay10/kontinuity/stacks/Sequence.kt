package io.github.kyay10.kontinuity.stacks

fun interface Sequence<out T> {
  operator fun iterator(): SuspendIterator<T>
}

fun <T> sequence(block: suspend context(Locality) SequenceScope<T>.() -> Unit): Sequence<T> = Sequence {
  iterator(block)
}

abstract class SequenceScope<in T> internal constructor() {
  context(_: Locality)
  abstract suspend fun yield(value: T)

  context(_: Locality)
  abstract suspend fun yieldAll(iterator: SuspendIterator<T>)

  context(_: Locality)
  suspend fun yieldAll(elements: Iterable<T>) {
    if (elements is Collection && elements.isEmpty()) return
    return yieldAll(elements.iterator().asSuspendIterator())
  }

  context(_: Locality)
  suspend fun yieldAll(sequence: Sequence<T>) = yieldAll(sequence.iterator())
}

fun <T> iterator(block: suspend context(Locality) SequenceScope<T>.() -> Unit): SuspendIterator<T> =
  object : AbstractSuspendIterator<T>() {
    var queued: SuspendIterator<T>? = null
    val stack: PausingStack = PausingStack { pause ->
      val scope =
        object : SequenceScope<T>() {
          context(_: Locality)
          override suspend fun yield(value: T) {
            setNext(value)
            pause()
          }

          context(_: Locality)
          override suspend fun yieldAll(iterator: SuspendIterator<T>) {
            if (iterator.hasNext()) {
              queued = iterator
              yield(iterator.next())
            }
          }
        }
      scope.block()
      done()
    }

    context(_: Locality)
    override suspend fun computeNext() {
      queued?.let {
        if (it.hasNext()) {
          setNext(it.next())
          return
        } else {
          queued = null
        }
      }
      val _ = stack.progress()
    }
  }
