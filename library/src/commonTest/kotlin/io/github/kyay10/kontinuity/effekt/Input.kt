package io.github.kyay10.kontinuity.effekt

fun interface Input {
  suspend fun read(): Char
}

context(input: Input)
suspend fun read(): Char = input.read()