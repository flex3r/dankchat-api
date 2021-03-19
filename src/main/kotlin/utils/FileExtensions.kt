package utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds

@Suppress("BlockingMethodInNonBlockingContext")
fun File.asWatchFlow(): Flow<File> = flow {
    val watchService = FileSystems.getDefault().newWatchService()
    val path = parentFile.toPath()
    path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

    emit(this@asWatchFlow)

    while (currentCoroutineContext().isActive) {
        val monitor = watchService.take()
        val dirPath = monitor.watchable() as? Path ?: break
        monitor.pollEvents().forEach {
            val eventFile = dirPath.resolve(it.context() as Path).toFile()
            if (eventFile.absolutePath != absolutePath) return@forEach

            emit(eventFile)
        }

        if (!monitor.reset()) {
            monitor.cancel()
            break
        }
    }
}.flowOn(Dispatchers.IO)
