package com.tibagni.logviewer

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class MockExecutorService : ExecutorService {
    override fun shutdown() {}

    override fun <T : Any?> submit(task: Callable<T>): Future<T> {
        val result = task.call()
        return CompletableFuture.completedFuture(result)
    }

    override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
        task.run()
        return CompletableFuture.completedFuture(result)
    }

    override fun submit(task: Runnable): Future<*> {
        task.run()
        return CompletableFuture.completedFuture(null)
    }

    override fun shutdownNow() = arrayListOf<Runnable>()

    override fun isShutdown(): Boolean {
        return false
    }

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        return true
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
        TODO("not implemented")
    }

    override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): T {
        TODO("not implemented")
    }

    override fun isTerminated(): Boolean {
        return false
    }

    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
        TODO("not implemented")
    }

    override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>, timeout: Long, unit: TimeUnit): MutableList<Future<T>> {
        TODO("not implemented")
    }

    override fun execute(command: Runnable) {
        command.run()
    }
}
