import kotlin.coroutines.Continuation
import kotlin.jvm.JvmInline

internal expect val Continuation<*>.isCompilerGenerated: Boolean
internal expect val Continuation<*>.completion: Continuation<*>
internal expect fun <T> Continuation<T>.copy(completion: Continuation<*>): Continuation<T>

internal fun <T, R> Continuation<T>.collectSubchain(prompt: Prompt<R>): Subchain<T, R> = Subchain(buildList {
  this@collectSubchain.forEach {
    add(it)
    if (it is Hole<*> && it.prompt == prompt) return@buildList
  }
})

internal inline fun Continuation<*>.forEach(block: (Continuation<*>) -> Unit) {
  var current: Continuation<*> = this
  while (true) {
    block(current)
    current = when (current) {
      in CompilerGenerated -> current.completion
      is CopyableContinuation -> current.completion
      else -> error("Continuation $current is not see-through, so its stack can't be traversed")
    }
  }
}

// list is a list of continuations from the current continuation to the hole
// The last element is the hole itself, the first element is the current continuation
@Suppress("UNCHECKED_CAST")
@JvmInline
internal value class Subchain<T, R>(private val list: List<Continuation<*>>) {
  fun replace(replacement: Continuation<R>): Continuation<T> {
    var result: Continuation<*> = replacement
    for (i in list.lastIndex - 1 downTo 0) result = when (val cont = list[i]) {
      in CompilerGenerated -> cont.copy(result)
      is CopyableContinuation -> cont.copy(result)
      else -> error("Continuation $this is not cloneable")
    }
    return result as Continuation<T>
  }

  val hole: Hole<R> get() = list.last() as Hole<R>
}

internal object CompilerGenerated {
  operator fun contains(cont: Continuation<*>): Boolean = cont.isCompilerGenerated
}

internal interface CopyableContinuation<T> : Continuation<T> {
  val completion: Continuation<*>
  fun copy(completion: Continuation<*>): CopyableContinuation<T>
}