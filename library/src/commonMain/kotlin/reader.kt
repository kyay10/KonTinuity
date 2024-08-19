import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

public suspend fun <T> Reader<T>.ask(): T = suspendCoroutineUninterceptedOrReturn {
  findNearestSplitSeq(it).find(this)
}

public suspend fun <T> Reader<T>.askOrNull(): T? = suspendCoroutineUninterceptedOrReturn {
  findNearestSplitSeq(it).findOrNull(this)
}

public suspend fun <T, R> runReader(value: T, fork: T.() -> T = { this }, body: suspend Reader<T>.() -> R): R =
  with(Reader<T>()) {
    pushReader(value, fork) { body() }
  }