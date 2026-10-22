package com.wallpaperhub.wallpaper

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.wallpaperhub.data.db.AppDatabase
import com.wallpaperhub.data.db.WallpaperEntity
import com.wallpaperhub.data.model.WallpaperType
import com.wallpaperhub.playback.engine.PlaybackOrderCoordinator
import com.wallpaperhub.playback.telemetry.WallpaperDebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.KeyAgreement

/**
 * 局域网 Wi-Fi 同步服务。
 * 启动 UDP 套接字监听 7884 端口（PC 发现），并开启 TCP 服务器绑定 7889 端口。
 * 实现 ECDH 密钥交换、PIN 配对以及接收 PC 传输的 .mpkg 壁纸包。
 *
 * 该服务是用户显式开启的临时配对会话，不应在进程重启后由系统自动恢复。
 */
class WifiSyncService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null

    inner class LocalBinder : Binder() {
        fun getService(): WifiSyncService = this@WifiSyncService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WifiSyncService onCreate")
        val random = java.util.Random()
        val generatedPin = "%04d".format(random.nextInt(10000))
        _pairingPin.value = generatedPin
        _ipAddress.value = getLocalIpAddress()
        _connectionState.value = "等待连接"
        Log.d(TAG, "生成配对 PIN 码: $generatedPin, IP 地址: ${_ipAddress.value}")
        startUdpServer()
        startTcpServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "WifiSyncService onDestroy")
        _connectionState.value = "未连接"
        serviceScope.cancel()
        closeSockets()
    }

    private fun startUdpServer() {
        serviceScope.launch {
            try {
                val socket = DatagramSocket(UDP_PORT)
                udpSocket = socket
                val buffer = ByteArray(1024)
                Log.d(TAG, "UDP 发现服务已启动，监听端口: $UDP_PORT")
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()
                    Log.d(TAG, "UDP 收到数据: $message")
                    if (message == "DISCOVER_WALLPAPERHUB") {
                        val deviceName = android.os.Build.MODEL
                        val replyMessage = "WALLPAPERHUB_DEVICE;$deviceName;$TCP_PORT"
                        val replyBytes = replyMessage.toByteArray()
                        socket.send(DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port))
                        Log.d(TAG, "UDP 已发送回复给 ${packet.address}:${packet.port}")
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "UDP 服务出错", e)
            }
        }
    }

    private fun startTcpServer() {
        serviceScope.launch {
            try {
                val server = ServerSocket(TCP_PORT)
                tcpServerSocket = server
                Log.d(TAG, "TCP 同步服务已启动，监听端口: $TCP_PORT")
                while (isActive) {
                    val socket = server.accept()
                    Log.d(TAG, "TCP 客户端已连接: ${socket.remoteSocketAddress}")
                    handleClientConnection(socket)
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "TCP 服务出错", e)
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        serviceScope.launch {
            try {
                _connectionState.value = "配对中"
                socket.use { client ->
                    client.soTimeout = 45_000
                    val dataInputStream = DataInputStream(client.getInputStream())
                    val dataOutputStream = DataOutputStream(client.getOutputStream())

                    val kpg = KeyPairGenerator.getInstance("EC")
                    kpg.initialize(256)
                    val localKeyPair = kpg.generateKeyPair()
                    val localPublicKeyBytes = localKeyPair.public.encoded
                    dataOutputStream.writeInt(localPublicKeyBytes.size)
                    dataOutputStream.write(localPublicKeyBytes)
                    dataOutputStream.flush()

                    val clientKeyLength = dataInputStream.readInt()
                    if (clientKeyLength <= 0 || clientKeyLength > 10_000) {
                        Log.e(TAG, "非法的客户端公钥长度: $clientKeyLength")
                        _connectionState.value = "等待连接"
                        return@launch
                    }
                    val clientKeyBytes = ByteArray(clientKeyLength)
                    dataInputStream.readFully(clientKeyBytes)

                    val factory = KeyFactory.getInstance("EC")
                    val clientPublicKey = factory.generatePublic(X509EncodedKeySpec(clientKeyBytes))
                    val agreement = KeyAgreement.getInstance("ECDH")
                    agreement.init(localKeyPair.private)
                    agreement.doPhase(clientPublicKey, true)
                    val sharedSecret = agreement.generateSecret()

                    val clientPinHash = dataInputStream.readUTF()
                    val expectedHash = computePinHash(_pairingPin.value, sharedSecret)
                    if (clientPinHash == expectedHash) {
                        Log.d(TAG, "PIN 配对验证成功！")
                        _connectionState.value = "已连接"
                        dataOutputStream.writeUTF("OK")
                        dataOutputStream.flush()
                    } else {
                        Log.w(TAG, "PIN 配对验证失败。接收到: $clientPinHash, 预期值: $expectedHash")
                        _connectionState.value = "等待连接"
                        dataOutputStream.writeUTF("ERROR")
                        dataOutputStream.flush()
                        return@launch
                    }

                    val fileName = dataInputStream.readUTF()
                    val fileSize = dataInputStream.readLong()
                    Log.d(TAG, "开始接收壁纸文件包: $fileName ($fileSize 字节)")

                    val tempFile = File(cacheDir, "sync_temp_${UUID.randomUUID()}.mpkg")
                    FileOutputStream(tempFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var bytesRemaining = fileSize
                        while (bytesRemaining > 0) {
                            val bytesToRead = minOf(buffer.size.toLong(), bytesRemaining).toInt()
                            val bytesRead = dataInputStream.read(buffer, 0, bytesToRead)
                            if (bytesRead == -1) break
                            fos.write(buffer, 0, bytesRead)
                            bytesRemaining -= bytesRead
                        }
                    }

                    val sandboxDir = filesDir.resolve("sandbox").resolve(UUID.randomUUID().toString())
                    sandboxDir.mkdirs()
                    unzip(tempFile, sandboxDir)
                    tempFile.delete()

                    val imported = registerWallpaperFromDir(sandboxDir, fileName)
                    if (imported != null) {
                        Log.d(TAG, "壁纸导入成功并已加入轮播: ${imported.name}")
                        _lastTransfer.value = "已导入并加入轮播 ${imported.name}"
                        dataOutputStream.writeUTF("SUCCESS")
                    } else {
                        Log.e(TAG, "解压后的目录中未找到有效壁纸资源，导入失败")
                        _lastTransfer.value = "导入失败：未找到可用媒体"
                        dataOutputStream.writeUTF("FAILED")
                    }
                    dataOutputStream.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理 TCP 客户端连接异常", e)
            } finally {
                if (_connectionState.value == "配对中" || _connectionState.value == "已连接") {
                    _connectionState.value = "等待连接"
                }
            }
        }
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun registerWallpaperFromDir(dir: File, defaultName: String): WallpaperEntity? {
        val files = dir.listFiles() ?: return null
        val videoExtensions = listOf("mp4", "mov", "mkv", "webm")
        val imageExtensions = listOf("jpg", "jpeg", "png", "webp", "gif")

        var assetFile = files.find { it.extension.lowercase() in videoExtensions }
        var type = WallpaperType.VIDEO
        if (assetFile == null) {
            assetFile = files.find { it.extension.lowercase() in imageExtensions }
            type = WallpaperType.STATIC
        }
        if (assetFile == null) {
            val allFiles = dir.walk().filter { it.isFile }.toList()
            assetFile = allFiles.find { it.extension.lowercase() in videoExtensions }
            if (assetFile != null) {
                type = WallpaperType.VIDEO
            } else {
                assetFile = allFiles.find { it.extension.lowercase() in imageExtensions }
                if (assetFile != null) type = WallpaperType.STATIC
            }
        }
        if (assetFile == null) return null

        val name = defaultName.substringBeforeLast(".")
        val uriStr = Uri.fromFile(assetFile).toString()
        var width: Int? = null
        var height: Int? = null
        var duration: Long? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, Uri.fromFile(assetFile))
            if (type == WallpaperType.VIDEO) {
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } else {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(assetFile.absolutePath, options)
                width = options.outWidth
                height = options.outHeight
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析元数据失败", e)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        val db = AppDatabase.getInstance(this)
        val entity = WallpaperEntity(
            id = UUID.randomUUID().toString(),
            uri = uriStr,
            type = type,
            name = name,
            addedAt = System.currentTimeMillis(),
            width = width,
            height = height,
            duration = duration,
        )
        val inserted = db.wallpaperDao().insert(entity)
        if (inserted != -1L) {
            val result = PlaybackOrderCoordinator.addMembers(this, listOf(entity))
            WallpaperDebugLog.i(
                "import",
                "wifi_import_auto_added_to_carousel",
                "uri" to WallpaperDebugLog.uri(entity.uri),
                "queueSize" to result.queueSize,
                "current" to WallpaperDebugLog.uri(result.currentUri),
                "revision" to result.revision,
            )
        }
        return entity
    }

    private fun computePinHash(pin: String, secret: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pin.toByteArray(Charsets.UTF_8))
        md.update(secret)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: ""
                        if (sAddr.indexOf(':') < 0) return sAddr
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取本地 IP 失败", e)
        }
        return "0.0.0.0"
    }

    private fun closeSockets() {
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        try {
            tcpServerSocket?.close()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WifiSyncService"
        private const val UDP_PORT = 7884
        private const val TCP_PORT = 7889

        private val _connectionState = MutableStateFlow("未连接")
        val connectionState: StateFlow<String> = _connectionState

        private val _pairingPin = MutableStateFlow("0000")
        val pairingPin: StateFlow<String> = _pairingPin

        private val _ipAddress = MutableStateFlow("0.0.0.0")
        val ipAddress: StateFlow<String> = _ipAddress

        private val _lastTransfer = MutableStateFlow("暂无记录")
        val lastTransfer: StateFlow<String> = _lastTransfer

        fun startPairing(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WifiSyncService::class.java)
            try {
                appContext.startService(intent)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "无法启动 PC 配对服务，请保持应用在前台后重试", e)
                _connectionState.value = "启动失败，请保持应用在前台重试"
            }
        }

        fun stopPairing(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, WifiSyncService::class.java)
            appContext.stopService(intent)
        }
    }
}
