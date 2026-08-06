package com.example.devmon

/** Flattens an exception's cause chain into one readable line, e.g. for GUI/log display. */
fun Throwable.describeCauseChain(): String =
    generateSequence(this) { it.cause }
        .take(4)
        .joinToString(" <- ") { it.message ?: it.javaClass.simpleName }
