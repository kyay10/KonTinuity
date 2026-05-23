package io.github.kyay10.kontinuity.stacks

fun interface Sequence<out T, in local> {
  operator fun iterator(): SuspendIterator<T, local>
}

interface SequenceFun<T, in local> {
  context(_: Locality<scope>)
  suspend operator fun <scope : local> SequenceScope<T, local, scope>.invoke()
}

context(_: Locality<scope>, scope: SequenceScope<T, local, scope>)
suspend operator fun <T, local, scope : local> SequenceFun<T, local>.invoke() = scope()

fun <T, sequence> sequence(block: SequenceFun<T, sequence>): Sequence<T, sequence> = Sequence { iterator(block) }

abstract class SequenceScope<in T, out owner, in local> internal constructor() {
  context(_: Locality<local>)
  abstract suspend fun yield(value: T)

  context(_: Locality<local>)
  abstract suspend fun yieldAll(iterator: SuspendIterator<T, owner>)

  context(_: Locality<local>)
  suspend fun yieldAll(elements: Iterable<T>) {
    if (elements is Collection && elements.isEmpty()) return
    return yieldAll(elements.iterator().asSuspendIterator())
  }

  context(_: Locality<local>)
  suspend fun yieldAll(sequence: Sequence<T, owner>) = yieldAll(sequence.iterator())
}

fun <T, block> iterator(block: SequenceFun<T, block>): SuspendIterator<T, block> =
  object : AbstractSuspendIterator<T, block>() {
    var queued: SuspendIterator<T, block>? = null
    val stack: PausingStack<block> =
      PausingStack(
        object : PausingBlock<block> {
          context(_: Locality<local2>)
          override suspend fun <local2 : block> invoke(pause: suspend context(Locality<local2>) () -> Unit) {
            val scope =
              object : SequenceScope<T, block, local2>() {
                context(_: Locality<local2>)
                override suspend fun yield(value: T) {
                  setNext(value)
                  pause()
                }

                context(_: Locality<local2>)
                override suspend fun yieldAll(iterator: SuspendIterator<T, block>) {
                  if (iterator.hasNext()) {
                    queued = iterator
                    yield(iterator.next())
                  }
                }
              }
            with(scope) { block() }
            done()
          }
        }
      )

    context(_: Locality<block>)
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
