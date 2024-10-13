package io.crisal.lightmesh

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.ConnectionInfoListener
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.crisal.lightmesh.ui.theme.LightmeshTheme
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket


const val TAG = "LightMesh"
fun log(s: String, throwable: Throwable? = null) {
    Log.v(TAG, s, throwable)
}

const val SERVICE_UUID = "8c030a58-76fa-455e-80db-c2d6082b56f9"

// TODO(emilio): It seems this could be rather simplified with Android 15:
// https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager.WifiP2pListener
// Of course that hasn't been released yet, so...
class WifiListener(private val m_owner: MainActivity) : WifiP2pManager.GroupInfoListener, WifiP2pManager.ChannelListener, WifiP2pManager.ConnectionInfoListener, WifiP2pManager.ActionListener, WifiP2pManager.PeerListListener, BroadcastReceiver() {
    var peers: Collection<WifiP2pDevice> = listOf();

    // ChannelListener
    override fun onChannelDisconnected() {
        log("onChannelDisconnected")
        m_owner.tryInitialize();
    }

    // GroupInfoListener
    override fun onGroupInfoAvailable(info: WifiP2pGroup?) {
        log("onGroupInfoAvailable: $info")
        Toast.makeText(m_owner, "Is group host: ${info?.isGroupOwner} addr: ${info?.owner?.deviceAddress}", Toast.LENGTH_SHORT).show()
    }

    // ConnectionInfoListener
    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        log("onConnectionInfoAvailable: $info")
        Toast.makeText(m_owner, "Group host: ${info.groupOwnerAddress?.hostAddress}", Toast.LENGTH_SHORT).show()
    }

    // ActionListener
    override fun onFailure(code: Int) {
        // XXX this also listeners for createGroup
        Toast.makeText(m_owner, "Discovery failed: $code", Toast.LENGTH_SHORT).show()
    }

    override fun onSuccess() {
        Toast.makeText(m_owner, "Discovery started", Toast.LENGTH_SHORT).show()
    }

    // PeerListener
    override fun onPeersAvailable(peers: WifiP2pDeviceList) {
        Toast.makeText(m_owner, "Peers available", Toast.LENGTH_SHORT).show()
        this.peers = peers.deviceList
    }

    // BroadcastReceiver
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION == action) {
            // UI update to indicate wifi p2p status.
            val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
            // Wifi Direct mode is enabled
            Toast.makeText(m_owner, "Wifi direct state change: ${state == WifiP2pManager.WIFI_P2P_STATE_ENABLED}", Toast.LENGTH_SHORT).show()
            // m_owner.setIsWifiP2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION == action) {
            Toast.makeText(m_owner, "P2P peers changed", Toast.LENGTH_SHORT).show()
            // Request available peers.
            m_owner.manager.requestPeers(m_owner.channel, this)
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION == action) {
            // TODO: Handle connect/disconnect?
        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION == action) {
            // val wifiP2pDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
            val wifiP2pDevice = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            Toast.makeText(m_owner, "P2P device changed: ${wifiP2pDevice}", Toast.LENGTH_LONG).show()
        }
    }
}

class BroadcastMessageTask(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        const val SOCKET_TIMEOUT = 5000
        const val KEY_MESSAGE: String = "KEY_MESSAGE"
        const val KEY_GROUP_OWNER_ADDRESS: String = "KEY_GROUP_OWNER_ADDRESS"
        const val KEY_GROUP_OWNER_PORT: String = "KEY_GROUP_OWNER_PORT"
    }

    override fun doWork(): Result {
        val host = inputData.getString(KEY_GROUP_OWNER_ADDRESS)!!
        val port = inputData.getInt(KEY_GROUP_OWNER_PORT, 8908)!!
        val socket = Socket()
        val message = inputData.getString(KEY_MESSAGE)!!

        try {
            log("Opening client socket - ")
            socket.bind(null)
            socket.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT)

            log("Client socket - ${socket.isConnected}")
            val stream = socket.getOutputStream()
            stream.write(message.toByteArray())

            log("Wrote message")
        } catch (e: IOException) {
            log("Error sending message", e)
            return Result.failure()
        } finally {
            if (socket.isConnected) {
                try {
                    socket.close()
                } catch (e: IOException) {
                    log("Error closing socket", e)
                }
            }
        }
        return Result.success()
    }
}

class MainActivity() : ComponentActivity() {
    private var m_manager: WifiP2pManager? = null;
    private var m_channel: WifiP2pManager.Channel? = null;

    private val m_intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    val listener = WifiListener(this);
    val channel: WifiP2pManager.Channel
        get() = m_channel!!
    val manager: WifiP2pManager
        get() = m_manager!!


    fun tryInitialize() {
        m_channel = manager.initialize(this, mainLooper, listener);

        manager.requestConnectionInfo(channel, listener)
        // manager.addLocalService(channel, WifiP2pUpnpServiceInfo.newInstance(SERVICE_UUID, "urn:schemas-upnp-org:device:MediaServer:1"))
        manager.discoverPeers(channel, listener)
        manager.createGroup(channel, listener)
        manager.requestGroupInfo(channel, listener)
        log("initialized successfully")
    }

    fun startWifiDirect() {
        // TODO: Sort out if we really need ACCESS_FINE_LOCATION, I think we can use android:usesPermissionFlags="neverForLocation".
        ActivityCompat.requestPermissions(
            this, arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
            0,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        m_manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager;
        tryInitialize()
        Toast.makeText(this, "Started wifi P2P service", Toast.LENGTH_SHORT).show()
    }

    fun broadcastMessage() {
        if (listener.peers.isEmpty()) {
            Toast.makeText(this, "No peers available", Toast.LENGTH_SHORT).show()
            return;
        }
        val peer = listener.peers.first()
        val config = WifiP2pConfig()
        config.deviceAddress = peer.deviceAddress
        config.wps.setup = WpsInfo.PBC
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.requestConnectionInfo(channel, object : WifiP2pManager.ConnectionInfoListener {
                    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
                        Toast.makeText(this@MainActivity, "Group host: ${info.groupOwnerAddress?.hostAddress}", Toast.LENGTH_SHORT).show()
                    }
                })
            }

            override fun onFailure(e: Int) {
                Toast.makeText(this@MainActivity, "Failed to connect to ${peer}", Toast.LENGTH_SHORT).show()
            }
        })

        // TODO
        val host = ""
        val port = 8000

        val task = OneTimeWorkRequest.Builder(BroadcastMessageTask::class.java)
            .setInputData(workDataOf(
                BroadcastMessageTask.KEY_MESSAGE to "My message",
                BroadcastMessageTask.KEY_GROUP_OWNER_ADDRESS to host,
                BroadcastMessageTask.KEY_GROUP_OWNER_PORT to port,
            ))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(this).enqueue(task.build())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LightmeshTheme {
                Scaffold( modifier = Modifier.fillMaxSize() ) { padding ->
                    Column(
                        modifier = Modifier.padding(padding),
                    ) {
                        Button(onClick = { startWifiDirect() }) {
                            Text("Start")
                        }
                        Button(onClick = { broadcastMessage() }) {
                            Text("Send message")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(listener, m_intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(listener)
    }
}
