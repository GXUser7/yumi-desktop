package com.mydrop.vpn.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Publish only durable writes: pairing acknowledgements must survive an immediate exit. */
class JsonStore<T>(private val file: File, private val serializer: KSerializer<T>, private val defaultValue: T,
    scope: CoroutineScope, private val onWriteFailure: (Throwable) -> Unit = {}) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    val value get() = mutable.value
    @Synchronized fun update(transform: (T) -> T) {
        val next = transform(value)
        try {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(serializer, next).toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            mutable.value = next
        } catch (error: Throwable) { onWriteFailure(error); throw error }
    }
    private fun read(): T {
        if (!file.exists()) return defaultValue
        return try { json.decodeFromString(serializer, file.readText()) } catch (error: Exception) {
            Files.copy(file.toPath(), File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis()).toPath())
            onWriteFailure(error)
            defaultValue
        }
    }
}
