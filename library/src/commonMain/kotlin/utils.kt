context(A) internal fun <A> given(): A = this@A

@Suppress("EqualsOrHashCode")
private object Flip {
  override fun equals(other: Any?): Boolean {
    // Not equal to itself
    return Keep == other
  }
}

private data object Keep

@PublishedApi internal fun updatingKey(shouldUpdate: Boolean): Any = if (shouldUpdate) Flip else Keep
