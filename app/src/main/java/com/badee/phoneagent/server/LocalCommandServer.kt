package com.badee.phoneagent.server

import com.badee.phoneagent.audit.AuditLog
import com.badee.phoneagent.protocol.AgentCommand
import com.badee.phoneagent.protocol.AgentResult
import com.badee.phoneagent.security.AuthTokenStore
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class LocalCommandServer(
    private val port: Int,
    private val tokenStore: AuthTokenStore,
    private val auditLog: AuditLog,
    private val execute: (AgentCommand) -> AgentResult,
) {
    private val running = AtomicBoolean(false)
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val clientExecutor = Executors.newFixedThreadPool(MAX_CLIENTS)
    private val rateLimiter = RateLimiter(MAX_COMMANDS_PER_MINUTE, 60_000L)
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptExecutor.execute {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), MAX_CLIENTS)
                serverSocket = socket
                while (running.get()) {
                    val client = socket.accept()
                    clientExecutor.execute { handle(client) }
                }
            } catch (error: SocketException) {
                if (running.get()) auditLog.record("server", false, error.message ?: "Socket error")
            } catch (error: Exception) {
                auditLog.record("server", false, error.message ?: "Server error")
            } finally {
                running.set(false)
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = CLIENT_TIMEOUT_MS
            val output = BufferedOutputStream(client.getOutputStream())
            val response = runCatching {
                if (!rateLimiter.tryAcquire()) {
                    return@runCatching AgentResult.failure("unknown", "Rate limit exceeded")
                }
                val raw = readLimitedLine(client, MAX_REQUEST_BYTES)
                val command = AgentCommand.parse(raw)
                if (!tokenStore.matches(command.token)) {
                    auditLog.record(command.action, false, "Authentication failed")
                    return@runCatching AgentResult.failure(command.id, "Authentication failed")
                }

                val result = runCatching { execute(command) }
                    .getOrElse { AgentResult.failure(command.id, it.message ?: "Command failed") }
                auditLog.record(command.action, result.ok, result.message)
                result
            }.getOrElse { error ->
                auditLog.record("invalid_request", false, error.message ?: "Invalid request")
                AgentResult.failure("unknown", error.message ?: "Invalid request")
            }

            output.write(response.toJson().toString().toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
            output.flush()
        }
    }

    private fun readLimitedLine(socket: Socket, limit: Int): String {
        val input = socket.getInputStream()
        val bytes = ArrayList<Byte>(minOf(limit, 4_096))
        while (bytes.size < limit) {
            val value = input.read()
            if (value == -1 || value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        require(bytes.size < limit) { "Request is too large" }
        require(bytes.isNotEmpty()) { "Empty request" }
        return bytes.toByteArray().toString(StandardCharsets.UTF_8)
    }

    private class RateLimiter(private val limit: Int, private val windowMs: Long) {
        private val timestamps = ArrayDeque<Long>()

        @Synchronized
        fun tryAcquire(): Boolean {
            val now = System.currentTimeMillis()
            while (timestamps.isNotEmpty() && now - timestamps.first() >= windowMs) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= limit) return false
            timestamps.addLast(now)
            return true
        }
    }

    private companion object {
        const val MAX_CLIENTS = 4
        const val MAX_COMMANDS_PER_MINUTE = 120
        const val MAX_REQUEST_BYTES = 128 * 1024
        const val CLIENT_TIMEOUT_MS = 15_000
    }
}
