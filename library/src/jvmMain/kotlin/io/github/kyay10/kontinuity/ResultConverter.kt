package io.github.kyay10.kontinuity

internal interface ResultConverter {
  fun Result<*>.convert(): Any?
}