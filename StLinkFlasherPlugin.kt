package com.example.stlinkflasher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

/**
 * Register in MainActivity:
 *   override fun configureFlutterEngine(engine: FlutterEngine) {
 *       super.configureFlutterEngine(engine)
 *       engine.plugins.add(StLinkFlasherPlugin())
 *   }
 */
class StLinkFlasherPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {

    private companion object {
        const val ACTION_PERMISSION = "com.example.stlinkflasher.USB_PERMISSION"
    }

    private lateinit var ctx: Context
    private lateinit var channel: MethodChannel
    private lateinit var events: EventChannel
    private var sink: EventChannel.EventSink? = null

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var link: StLink? = null
    private var target: Stm32F1? = null

    // ---------- FlutterPlugin ----------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        ctx = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "stlink_flasher")
        channel.setMethodCallHandler(this)
        events = EventChannel(binding.binaryMessenger, "stlink_flasher/progress")
        events.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        events.setStreamHandler(null)
        io.execute { closeLink() }
    }

    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) { sink = eventSink }
    override fun onCancel(arguments: Any?) { sink = null }

    // ---------- helpers ----------

    private fun usb() = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun emit(stage: String, percent: Int) {
        main.post { sink?.success(mapOf("stage" to stage, "percent" to percent)) }
    }

    private fun t(): Stm32F1 = target ?: throw StLinkException("Not connected to an ST-Link.")

    private fun closeLink() {
        runCatching { target?.reset() }
        runCatching { link?.close() }
        link = null
        target = null
    }

    /** Runs [block] on the single IO thread so USB transfers never overlap or block the UI. */
    private fun work(result: MethodChannel.Result, block: () -> Any?) {
        io.execute {
            try {
                val r = block()
                main.post { result.success(r) }
            } catch (e: Exception) {
                main.post { result.error("STLINK", e.message ?: e.toString(), null) }
            }
        }
    }

    // ---------- MethodCallHandler ----------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "listDevices" -> result.success(
                usb().deviceList.values.filter { StLink.isStLink(it) }.map {
                    mapOf("name" to it.deviceName, "vid" to it.vendorId, "pid" to it.productId)
                }
            )

            "connect" -> connect(
                call.argument<String>("name"),
                call.argument<Boolean>("underReset") ?: false,
                result,
            )

            "targetInfo" -> work(result) { t().info().toMap() }

            "diagnose" -> work(result) { t().diagnose().map { it.toMap() } }

            "backup" -> work(result) {
                t().backup(call.argument<Int>("length")) { s, p -> emit(s, p) }
            }

            "flash" -> {
                val data = call.argument<ByteArray>("data")
                if (data == null) {
                    result.error("ARGS", "Missing 'data'", null)
                    return
                }
                val verify = call.argument<Boolean>("verify") ?: true
                val skip = call.argument<Boolean>("skipImageCheck") ?: false
                work(result) {
                    t().flash(data, verify, skip) { s, p -> emit(s, p) }
                    true
                }
            }

            "eraseAll" -> work(result) {
                t().eraseAll { s, p -> emit(s, p) }
                true
            }

            "removeReadProtection" -> {
                if (call.argument<Boolean>("confirm") != true) {
                    result.error("CONFIRM", "Removing read protection erases the stock firmware; pass confirm = true.", null)
                    return
                }
                work(result) {
                    t().removeReadProtection { s, p -> emit(s, p) }
                    true
                }
            }

            "disconnect" -> work(result) {
                closeLink()
                true
            }

            else -> result.notImplemented()
        }
    }

    // ---------- connect + USB permission ----------

    private fun connect(name: String?, underReset: Boolean, result: MethodChannel.Result) {
        val m = usb()
        val dev = m.deviceList.values.firstOrNull {
            StLink.isStLink(it) && (name == null || it.deviceName == name)
        }
        if (dev == null) {
            result.error("NO_DEVICE", "No ST-Link found. Check the OTG cable and adapter.", null)
            return
        }
        if (m.hasPermission(dev)) {
            openDevice(m, dev, underReset, result)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { ctx.unregisterReceiver(this) }
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openDevice(m, dev, underReset, result)
                } else {
                    result.error("PERMISSION", "USB permission was denied.", null)
                }
            }
        }
        // Android 14 requires an explicit exported flag; older versions don't have the overload.
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, IntentFilter(ACTION_PERMISSION))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags
        )
        m.requestPermission(dev, pi)
    }

    private fun openDevice(
        m: UsbManager,
        dev: UsbDevice,
        underReset: Boolean,
        result: MethodChannel.Result,
    ) =
        work(result) {
            closeLink()
            val l = StLink(m, dev)
            val info = l.open(underReset)
            link = l
            target = Stm32F1(l)
            mapOf(
                "stlinkVersion" to info.stlinkVer,
                "jtagVersion" to info.jtagVer,
                "vid" to info.vid,
                "pid" to info.pid,
            )
        }
}
