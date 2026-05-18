package io.github.kyay10.kontinuity.stacks

fun interface Sequence<out T> {
  suspend operator fun iterator(): SuspendIterator<T>
}

fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence { iterator(block) }

abstract class SequenceScope<in T> internal constructor() {
  abstract suspend fun yield(value: T)

  abstract suspend fun yieldAll(iterator: SuspendIterator<T>)

  suspend fun yieldAll(elements: Iterable<T>) {
    if (elements is Collection && elements.isEmpty()) return
    return yieldAll(elements.iterator().asSuspendIterator())
  }

  suspend fun yieldAll(sequence: Sequence<T>) = yieldAll(sequence.iterator())
}

suspend fun <T> iterator(block: suspend SequenceScope<T>.() -> Unit): SuspendIterator<T> =
  object : AbstractSuspendIterator<T>() {
      var queued: SuspendIterator<T>? = null
      lateinit var stack: PausingStack

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

      suspend fun init() {
        stack = PausingStack { pause ->
          val scope =
            object : SequenceScope<T>() {
              override suspend fun yield(value: T) {
                setNext(value)
                pause()
              }

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
      }
    }
    .apply { init() }
