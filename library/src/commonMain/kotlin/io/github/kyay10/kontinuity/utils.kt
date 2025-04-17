package io.github.kyay10.kontinuity

internal inline fun <T> T.letIf(
  condition: Boolean,
  block: (T) -> T
): T = if (condition) block(this) else this

internal inline fun <T> T.letUnless(
  condition: Boolean,
  block: (T) -> T
): T = if (!condition) block(this) else this