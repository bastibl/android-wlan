package net.bastibl.wlan

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import kotlinx.android.synthetic.main.activity_main.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDeviceConnection
import android.util.Log

import com.androidplot.xy.XYSeries
import com.androidplot.xy.*

import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.androidplot.ui.*
import com.androidplot.util.Redrawer

import org.gnuradio.controlport.complex
import org.gnuradio.grcontrolport.RPCConnectionThrift
import org.zeromq.SocketType
import org.zeromq.ZMQ
import org.zeromq.ZContext
import java.lang.ref.WeakReference

import java.util.HashMap
import kotlin.concurrent.thread

private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"

class MainActivity : AppCompatActivity() {

    private val usbReceiver = object : BroadcastReceiver() {

        @Suppress("IMPLICIT_CAST_TO_ANY")
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            setupUSB(device)
                        }
                    } else {
                        Log.d("GR", "permission denied for device $device")
                    }
                }
            }
        }
    }

    private var redrawer :Redrawer? = null

    private fun checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                123)
        } else {
            checkHWPermission()
        }
    }

    private fun checkHWPermission() {

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList: HashMap<String, UsbDevice> = manager.deviceList
        deviceList.values.forEach { device ->
            if(device.vendorId == 0x2500) {
                val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 0)
                val filter = IntentFilter(ACTION_USB_PERMISSION)
                registerReceiver(usbReceiver, filter)

                manager.requestPermission(device, permissionIntent)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            123 -> {
                checkHWPermission()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        plot.setRangeBoundaries(-2, 2, BoundaryMode.FIXED)
        plot.setDomainBoundaries(-2, 2, BoundaryMode.FIXED)

        plot.graph.size = Size(0f, SizeMode.FILL, 0f, SizeMode.FILL)
        plot.graph.position(0f, HorizontalPositioning.ABSOLUTE_FROM_LEFT, 0f, VerticalPositioning.ABSOLUTE_FROM_TOP)

        // background
        plot.borderPaint.color = Color.parseColor("#335588")
        plot.graph.backgroundPaint.color = Color.parseColor("#335588")
        plot.graph.gridBackgroundPaint.color = Color.parseColor("#335588")

        // grid lines
        plot.graph.rangeGridLinePaint.color = Color.GRAY
        plot.graph.domainGridLinePaint.color = Color.GRAY
        plot.graph.domainOriginLinePaint.color = Color.GRAY
        plot.graph.rangeOriginLinePaint.color = Color.GRAY

        // axis label
        plot.graph.getLineLabelStyle(XYGraphWidget.Edge.LEFT).paint.color = Color.TRANSPARENT
        plot.graph.getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).paint.color = Color.TRANSPARENT
        plot.rangeTitle.labelPaint.color = Color.GRAY
        plot.domainTitle.labelPaint.color = Color.GRAY
        plot.title.labelPaint.color = Color.TRANSPARENT

        plot.domainTitle.position(0f, HorizontalPositioning.ABSOLUTE_FROM_CENTER, 40f, VerticalPositioning.ABSOLUTE_FROM_BOTTOM)
        plot.rangeTitle.position(40f, HorizontalPositioning.ABSOLUTE_FROM_LEFT, 00f, VerticalPositioning.ABSOLUTE_FROM_CENTER)
        plot.domainTitle.anchor = Anchor.CENTER
        plot.rangeTitle.anchor = Anchor.CENTER

        plot.graph.setMargins(0f, 0f, 0f, 0f)
        plot.graph.setPadding(20f, 40f, 40f, 40f)

        thread(start=true) {

            val context = ZContext()
            val subscriber = context.createSocket(SocketType.SUB)
            subscriber.connect("tcp://127.0.0.1:5503")
            subscriber.subscribe("".toByteArray(ZMQ.CHARSET))
            var i = 0

            while (!Thread.currentThread().isInterrupted) {
                val address = subscriber.recv()
                i += 1
                runOnUiThread {
                    sample_text.text = "received frames: " + i.toString()
                }
            }
        }

        checkStoragePermission()
    }

    class ECGModel(size: Int, updateFreqHz: Int) : XYSeries {

        private var data : ArrayList<complex> = ArrayList<complex>(size)
        private var delayMs = 1000L / updateFreqHz;
        private var i = 0;
        private var thread : Thread;
        private var keepRunning = true

        private lateinit var rendererRef : WeakReference<AdvancedLineAndPointRenderer>

        init {

            thread = Thread(Runnable {

                while(true) {
                    try {
                        val rpcConnection = RPCConnectionThrift("localhost", 65001)
                        val probeName = "probe2_c0::const"
                        val a = ArrayList<String>(1)
                        a.add(probeName)

                        while (keepRunning) {
                            val knobs = rpcConnection.getKnobs(a)
                            data = knobs[probeName]!!.value as ArrayList<complex>

                            Thread.sleep(delayMs);
                        }
                    } catch (e : Throwable) {
                        Log.d("rpc", "no ready, waiting to retry")
                        Thread.sleep(250)
                    }
                }
            })
        }

        fun start(rendererRef : WeakReference<AdvancedLineAndPointRenderer>) {
            this.rendererRef = rendererRef
            keepRunning = true
            thread.start()
        }

        override fun size() : Int {
            return data.size
        }

        override fun getX(index : Int) : Number {
            return data[index].re
        }

        override fun getY(index : Int) : Number {
            return data[index].im
        }

        override fun getTitle() : String {
            return "Signal"
        }
    }

    @SuppressLint("SetTextI18n")
    fun setupUSB(usbDevice: UsbDevice) {

        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val connection: UsbDeviceConnection = manager.openDevice(usbDevice)

        val fd = connection.fileDescriptor

        val usbfsPath = usbDevice.deviceName

        val vid = usbDevice.vendorId
        val pid = usbDevice.productId

        Log.d("gnuradio", "#################### NEW RUN ###################")
        Log.d("gnuradio", "Found fd: $fd  usbfs_path: $usbfsPath")
        Log.d("gnuradio", "Found vid: $vid  pid: $pid")

        sample_text.text =
            "Found fd: $fd  usbfsPath: $usbfsPath vid: $vid  pid: $pid"

        thread(start = true, priority = Thread.MAX_PRIORITY) {
            fgInit(fd, usbfsPath)
            fgStart(cacheDir.absolutePath)
        }

//        seekBar.max = 15
//        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
//                setFreq(5.170e9 + seekBar.progress * 10e6)
//                sample_text.text = "Freq %f".format(5.170e9 + seekBar.progress * 10e6)
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar) {}
//        })
//
//        seekBar.progress = 15

        val ecgSeries = ECGModel(240, 15)
        val formatter = LineAndPointFormatter(null, Color.parseColor("#F44336"), null, null)
        formatter.isLegendIconEnabled = false

        plot.addSeries(ecgSeries, formatter)

        // reduce the number of range labels
        plot.linesPerRangeLabel = 3

        ecgSeries.start(WeakReference<AdvancedLineAndPointRenderer>(plot.getRenderer(AdvancedLineAndPointRenderer::class.java)))
        redrawer = Redrawer(plot, 15.0f, true)
    }

    override fun onStop() {
        super.onStop()
        this.redrawer?.finish()
        fgStop()
    }

    private external fun fgInit(fd: Int, usbfsPath: String): Void
    private external fun fgStart(tmpName: String): Void
    private external fun fgStop(): Void
    external fun fgRep(): String
    external fun setFreq(freq : Double): Void

    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }
}
