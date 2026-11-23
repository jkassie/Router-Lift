package com.routerlift.control.lift

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic serial (SPP) link to the lift. The caller must hold BLUETOOTH_CONNECT.
 * Reads block on the socket, so [close] is the only way to end [lines] early.
 */
@SuppressLint("MissingPermission")
class LiftLink(private val adapter: BluetoothAdapter) {
    @Volatile
    private var socket: BluetoothSocket? = null

    /** The lift only shows up here once it has been paired in Android's Bluetooth settings. */
    fun findPairedLift(): BluetoothDevice? =
        adapter.bondedDevices.orEmpty().firstOrNull {
            it.name?.replace("\r", "")?.replace("\n", "")?.trim().equals(DEVICE_NAME, ignoreCase = true)
        }

    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        // No cancelDiscovery() here: it needs BLUETOOTH_SCAN, and this app never starts discovery.
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        socket = s
        s.connect()
    }

    /** Emits each line the lift sends; completes when the connection closes. */
    fun lines(): Flow<String> = flow {
        val reader = (socket ?: throw IOException("Not connected")).inputStream.bufferedReader(Charsets.US_ASCII)
        while (true) {
            val line = reader.readLine() ?: break
            emit(line)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun send(command: String) = withContext(Dispatchers.IO) {
        val out = (socket ?: throw IOException("Not connected")).outputStream
        out.write((command + "\n").toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    companion object {
        const val DEVICE_NAME = "mlcs-powerlift-pro"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
