/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher

import android.app.ActivityOptions
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothA2dp
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioManager
import android.content.Intent
import android.icu.text.DateFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TransportInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManagerGlobal
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import androidx.preference.PreferenceManager
import org.lineageos.tv.launcher.ext.NetworkState
import org.lineageos.tv.launcher.ext.panelShortcutEnabled
import org.lineageos.tv.launcher.ext.networkCallbackFlow
import org.lineageos.tv.launcher.notification.NotificationAdapter
import org.lineageos.tv.launcher.notification.NotificationUtils
import org.lineageos.tv.launcher.notification.ServiceConnectionState
import org.lineageos.tv.launcher.utils.AppManager
import org.lineageos.tv.launcher.view.NotificationItemView
import org.lineageos.tv.launcher.view.TwoLineButton
import org.lineageos.tv.launcher.viewmodels.NotificationViewModel
import java.util.Calendar

class SystemOptionsActivity : ModalActivity(R.layout.activity_system_options),
    NotificationAdapter.OnItemActionListener {
    // View model
    private val notificationViewModel: NotificationViewModel by viewModels()

    // Views
    private val accessibilityTwoLineButton by lazy { findViewById<TwoLineButton>(R.id.accessibilityTwoLineButton)!! }
    private val allowNotificationAccessMaterialButton by lazy { findViewById<MaterialButton>(R.id.allowNotificationAccessMaterialButton)!! }
    private val audioOutputTwoLineButton by lazy { findViewById<TwoLineButton>(R.id.audioOutputTwoLineButton)!! }
    private val bluetoothTwoLineButton by lazy { findViewById<TwoLineButton>(R.id.bluetoothTwoLineButton)!! }
    private val dateTextView by lazy { findViewById<TextView>(R.id.dateTextView)!! }
    private val networkTwoLineButton by lazy { findViewById<TwoLineButton>(R.id.networkTwoLineButton)!! }
    private val noNotificationAccessLinearLayout by lazy { findViewById<LinearLayout>(R.id.noNotificationAccessLinearLayout)!! }
    private val noNotificationsTextView by lazy { findViewById<TextView>(R.id.noNotificationsTextView)!! }
    private val notificationsVerticalGridView by lazy { findViewById<VerticalGridView>(R.id.notificationsVerticalGridView)!! }
    private val panelShortcutTwoLineButton by lazy { findViewById<TwoLineButton>(R.id.panelShortcutTwoLineButton)!! }
    private val powerMaterialButton by lazy { findViewById<MaterialButton>(R.id.powerMaterialButton)!! }
    private val screensaverTwoLineButton by lazy { findViewById<TwoLineButton>(R.id.screensaverTwoLineButton)!! }
    private val settingsButton by lazy { findViewById<MaterialButton>(R.id.settingsMaterialButton)!! }
    private val sleepMaterialButton by lazy { findViewById<MaterialButton>(R.id.sleepMaterialButton)!! }

    private val notificationAdapter: NotificationAdapter by lazy { NotificationAdapter(this, this) }

    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java)!! }

    private val sharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this)!! }

    /**
     * The A2DP profile proxy, bound asynchronously. Needed because
     * AudioManager can only see the Bluetooth sink that is currently routed,
     * not the ones that are connected and idle — and a picker has to offer
     * both. Null until the service binds, and the tile repaints when it does.
     */
    private var a2dp: BluetoothA2dp? = null

    private val a2dpListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = proxy as BluetoothA2dp
                setAudioOutputButton()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = null
            }
        }
    }

    /**
     * Switching output is asynchronous, and a sink can also appear or vanish on
     * its own. Repaint from the platform rather than assuming the switch we
     * asked for is the one that happened.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
            setAudioOutputButton()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
            setAudioOutputButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Animate
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.slide_in_right,
                R.anim.slide_out_right
            )
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.slide_in_right,
                R.anim.slide_out_right
            )
        }

        // Date
        val currentDate = Calendar.getInstance().time
        dateTextView.text = DateFormat.getPatternInstance(DateFormat.YEAR_ABBR_MONTH_WEEKDAY_DAY)
            .format(currentDate)

        // Wifi & Bluetooth
        setNetworkButton()
        setBluetoothButton()
        setPanelShortcutButton()
        setAccessibilityButton()
        setScreensaverButton()
        setAudioOutputButton()

        getSystemService(BluetoothManager::class.java)?.adapter
            ?.getProfileProxy(this, a2dpListener, BluetoothProfile.A2DP)
        getSystemService(AudioManager::class.java)
            ?.registerAudioDeviceCallback(audioDeviceCallback, null)

        settingsButton.setOnClickListener {
            startActivity(SETTINGS)
        }

        allowNotificationAccessMaterialButton.setOnClickListener {
            startActivity(NOTIFICATION_SETTINGS)
        }

        notificationsVerticalGridView.adapter = notificationAdapter

        if (AppManager.isSystemApp(this)) {
            sleepMaterialButton.setOnClickListener {
                val pm: PowerManager = getSystemService(PowerManager::class.java) as PowerManager
                pm.goToSleep(
                    SystemClock.uptimeMillis(),
                    PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON,
                    0
                )
            }

            powerMaterialButton.setOnClickListener {
                val wm = WindowManagerGlobal.getWindowManagerService()
                wm?.showGlobalActions()
            }
        } else {
            sleepMaterialButton.visibility = View.GONE
            powerMaterialButton.visibility = View.GONE
        }

        // WIFI callbacks
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        lifecycleScope.launch {
            connectivityManager.networkCallbackFlow(request).collect {
                when (it) {
                    is NetworkState.Available -> setNetworkButton(
                        capabilities = connectivityManager.getNetworkCapabilities(it.network)
                    )

                    is NetworkState.Lost -> setNetworkButton(
                        capabilities = connectivityManager.getNetworkCapabilities(it.network)
                    )

                    is NetworkState.CapabilitiesChanged ->
                        setNetworkButton(
                            transportInfo = it.networkCapabilities.transportInfo,
                            capabilities = it.networkCapabilities
                        )
                }
            }
        }

        networkTwoLineButton.setOnClickListener {
            startActivity(WIFI_SETTINGS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                notificationViewModel.state.collect { state ->
                    when (state) {
                        is ServiceConnectionState.Connected -> {
                            // Ignore
                        }

                        is ServiceConnectionState.Disconnected -> {
                            if (NotificationUtils.notificationPermissionGranted(this@SystemOptionsActivity)) {
                                notificationAdapter.submitList(emptyList())
                                noNotificationsTextView.visibility = View.VISIBLE
                            } else {
                                noNotificationAccessLinearLayout.visibility = View.VISIBLE
                                noNotificationsTextView.visibility = View.GONE
                            }
                            notificationsVerticalGridView.visibility = View.GONE
                        }

                        is ServiceConnectionState.Notifications -> {
                            if (state.notifications.isEmpty() || state.currentRanking == null) {
                                noNotificationsTextView.visibility = View.VISIBLE
                                notificationsVerticalGridView.visibility = View.GONE
                                return@collect
                            }

                            val statusBarNotifications = ArrayList<StatusBarNotification>()
                            for (key in state.currentRanking.orderedKeys) {
                                val sbn: StatusBarNotification? = state.notifications[key]
                                if (sbn != null) {
                                    statusBarNotifications.add(sbn)
                                }
                            }

                            notificationAdapter.submitList(statusBarNotifications)
                            noNotificationsTextView.visibility = View.GONE
                            notificationsVerticalGridView.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!NotificationUtils.notificationPermissionGranted(this)) {
            noNotificationAccessLinearLayout.visibility = View.VISIBLE
            noNotificationsTextView.visibility = View.GONE
            notificationsVerticalGridView.visibility = View.GONE
            return
        }

        notificationViewModel.bindService(this)
    }

    override fun onResume() {
        super.onResume()
        if (NotificationUtils.notificationPermissionGranted(this)) {
            noNotificationAccessLinearLayout.visibility = View.GONE
        } else {
            noNotificationAccessLinearLayout.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (NotificationUtils.notificationPermissionGranted(this)) {
            notificationViewModel.unbindService(this)
        }

        getSystemService(AudioManager::class.java)
            ?.unregisterAudioDeviceCallback(audioDeviceCallback)
        a2dp?.let {
            getSystemService(BluetoothManager::class.java)?.adapter
                ?.closeProfileProxy(BluetoothProfile.A2DP, it)
            a2dp = null
        }
    }

    private fun setNetworkButton(
        transportInfo: TransportInfo? = null,
        capabilities: NetworkCapabilities? = connectivityManager.getNetworkCapabilities(
            connectivityManager.activeNetwork
        )
    ) {
        val networkString: String
        val networkIcon: Int
        if (capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            // No internet connection
            networkString = resources.getString(R.string.not_connected)
            networkIcon = R.drawable.ic_wifi_not_connected
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            // Ethernet connection
            networkString = resources.getString(R.string.connected)
            networkIcon = R.drawable.ic_ethernet
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            // WIFI connection
            if (transportInfo is WifiInfo) {
                val wifiManager = getSystemService(WifiManager::class.java)!!
                val wifiStrength = wifiManager.calculateSignalLevel(transportInfo.rssi)
                networkString = resources.getString(R.string.connected)
                networkIcon = wifiIcons[wifiStrength.coerceIn(0, wifiIcons.size - 1)]
            } else {
                networkString = resources.getString(R.string.not_connected)
                networkIcon = R.drawable.ic_wifi_not_connected
            }
        } else {
            // Unknown transport type
            networkString = resources.getString(R.string.unknown)
            networkIcon = R.drawable.ic_wifi_not_connected
        }

        networkTwoLineButton.icon = AppCompatResources.getDrawable(this, networkIcon)

        val networkSpan =
            SpannableString(resources.getString(R.string.network_status, networkString))
        networkTwoLineButton.setSpan(networkSpan)
    }

    /**
     * The outputs a user can actually choose between on this box: the built-in
     * one (HDMI here, the speaker on hardware that has one) and every connected
     * Bluetooth sink.
     *
     * [device] is null for the built-in output, which is how the platform
     * models it too — routing to HDMI is "no active Bluetooth audio device"
     * rather than a device of its own.
     */
    private data class AudioOutput(val label: String, val device: BluetoothDevice?)

    /**
     * Live state comes from AudioManager, which is public API. Only the
     * *switch* needs the privileged calls below, so a build where those are
     * refused still shows the right thing.
     */
    private fun audioOutputs(): List<AudioOutput> {
        val audioManager = getSystemService(AudioManager::class.java)
        val types = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.map { it.type }
            ?.toSet()
            ?: emptySet()

        val builtIn = resources.getString(
            when {
                types.contains(AudioDeviceInfo.TYPE_WIRED_HEADPHONES) ||
                        types.contains(AudioDeviceInfo.TYPE_WIRED_HEADSET) ->
                    R.string.audio_output_headphones

                types.contains(AudioDeviceInfo.TYPE_HDMI) ||
                        types.contains(AudioDeviceInfo.TYPE_HDMI_ARC) ||
                        types.contains(AudioDeviceInfo.TYPE_HDMI_EARC) ->
                    R.string.audio_output_hdmi

                types.contains(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) ->
                    R.string.audio_output_speaker

                else -> R.string.audio_output_unknown
            }
        )

        // Bonded rather than connected. getDevices() lists a sink only while
        // it is the active route; a2dp.connectedDevices adds the idle-but-
        // connected ones — but deactivating a sink also drops its profile
        // connection, so a "connected" list empties the moment you switch to
        // HDMI and you can never switch back. Everything bonded that can play
        // audio is the set a user means by "my speakers".
        val sinks = getSystemService(BluetoothManager::class.java)?.adapter
            ?.bondedDevices.orEmpty()
            .filter { isAudioSink(it) }
            .map { AudioOutput(bluetoothLabel(it), it) }

        return listOf(AudioOutput(builtIn, null)) + sinks
    }

    /**
     * A2DP support is the thing that matters, but reading the UUID list needs
     * the device to have been queried. The class of device is always there and
     * is what the accessory UI uses, so go by that and let a false positive be
     * a row that simply does not work rather than a speaker that never appears.
     */
    private fun isAudioSink(device: BluetoothDevice) = try {
        device.bluetoothClass?.let {
            it.hasService(BluetoothClass.Service.AUDIO) ||
                    it.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        } == true
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "no permission to read a bonded device's class", e)
        false
    }

    private fun bluetoothLabel(device: BluetoothDevice) = try {
        device.alias ?: device.name ?: device.address
    } catch (e: SecurityException) {
        // BLUETOOTH_CONNECT is default-granted to this package, but do not take
        // an unnamed device down the whole panel with it.
        Log.w(LOG_TAG, "no permission to read the name of a bonded device", e)
        device.address
    }

    /** The output audio is on now, as an index into [audioOutputs]. */
    private fun activeOutputIndex(outputs: List<AudioOutput>): Int {
        val audioManager = getSystemService(AudioManager::class.java)
        val activeAddress = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?.address
            ?: return 0

        val matched = outputs.indexOfFirst { it.device?.address == activeAddress }
        if (matched >= 0) {
            return matched
        }

        // Audio is on a Bluetooth sink we did not list — it disconnected
        // between the two reads, or A2DP has not bound yet. Point at any sink
        // rather than claim HDMI while sound comes out of a speaker.
        val anySink = outputs.indexOfFirst { it.device != null }
        return if (anySink >= 0) anySink else 0
    }

    private fun setAudioOutputButton() {
        val outputs = audioOutputs()
        val active = outputs.getOrNull(activeOutputIndex(outputs))

        audioOutputTwoLineButton.setSpan(
            SpannableString(
                resources.getString(
                    R.string.audio_output_status,
                    active?.label ?: resources.getString(R.string.audio_output_unknown)
                )
            )
        )

        audioOutputTwoLineButton.setOnClickListener { showAudioOutputPicker() }
    }

    private fun showAudioOutputPicker() {
        val outputs = audioOutputs()

        // One output is not a choice. Send the user somewhere they can pair a
        // speaker rather than showing them a list of one.
        if (outputs.size < 2) {
            startActivity(BLUETOOTH_SETTINGS)
            return
        }

        val labels = outputs.map { it.label }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.audio_output_title)
            .setSingleChoiceItems(labels, activeOutputIndex(outputs)) { d, which ->
                d.dismiss()
                selectAudioOutput(outputs[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // The panel is a right-edge pop-over, so a dialog centred on the screen
        // reads as a different surface entirely. Keep it over the panel.
        dialog.window?.let { window ->
            window.attributes = window.attributes.apply { gravity = Gravity.END }
            window.setLayout(
                resources.getDimensionPixelSize(R.dimen.audio_output_dialog_width),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    /**
     * Switching output *is* activating or deactivating a Bluetooth sink: A2DP
     * outranks HDMI in the platform's routing policy, so "use HDMI" means "have
     * no active Bluetooth audio device". This is the same pair of calls
     * TvSettings uses from AccessoryUtils.
     *
     * Both need BLUETOOTH_PRIVILEGED, which this package is allowlisted for.
     */
    private fun selectAudioOutput(output: AudioOutput) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.w(LOG_TAG, "no Bluetooth adapter; cannot change audio output")
            return
        }

        val switched = try {
            output.device?.let { device ->
                // A sink that is bonded but not connected cannot be made
                // active. Connecting it is enough: the stack makes a freshly
                // connected A2DP device the active one.
                if (a2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                    adapter.setActiveDevice(device, BluetoothAdapter.ACTIVE_DEVICE_AUDIO)
                } else {
                    // BluetoothA2dp.connect is @hide and Bluetooth is a
                    // mainline module, so it is not in the stubs even for a
                    // platform app. BluetoothDevice.connect is the @SystemApi
                    // equivalent, and wants the same three permissions.
                    device.connect() == BluetoothStatusCodes.SUCCESS
                }
            } ?: adapter.removeActiveDevice(BluetoothAdapter.ACTIVE_DEVICE_AUDIO)
        } catch (e: SecurityException) {
            Log.e(LOG_TAG, "not allowed to change the active audio device", e)
            false
        }

        if (!switched) {
            Log.w(LOG_TAG, "refused to switch audio output to ${output.label}")
        }
        // Routing is asynchronous either way; audioDeviceCallback repaints the
        // tile when it lands, so there is nothing to update here.
    }

    private fun setScreensaverButton() {
        // screensaver_components is unset out of the box, so the screensaver
        // looks enabled while having nothing to show. With nothing selected
        // there is nothing to start, so send the user to the picker instead.
        val component = Settings.Secure.getString(
            contentResolver, SCREENSAVER_COMPONENTS
        )
        val configured = !component.isNullOrEmpty()

        screensaverTwoLineButton.setSpan(
            SpannableString(
                resources.getString(
                    R.string.screensaver_status,
                    resources.getString(
                        if (configured) R.string.screensaver_start
                        else R.string.screensaver_not_set
                    )
                )
            )
        )

        screensaverTwoLineButton.setOnClickListener {
            if (configured) {
                // Blank the box now — the point of the tile is to park a
                // MythTV front end without waiting out the idle timer.
                // DaydreamVoiceAction is exported and calls startDreaming()
                // for us, which keeps this out of needing WRITE_DREAM_STATE.
                startActivity(SCREENSAVER_START)
                finish()
            } else {
                startActivity(SCREENSAVER_SETTINGS)
            }
        }

        screensaverTwoLineButton.setOnLongClickListener {
            startActivity(SCREENSAVER_SETTINGS)
            true
        }
    }

    private fun setAccessibilityButton() {
        // Show whether anything is actually assisting, not just that the screen
        // exists: "Accessibility / No services on" is the common case on a TV
        // box and is worth being able to see at a glance.
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val status = when {
            enabledServices.isNullOrEmpty() -> resources.getString(R.string.accessibility_none)
            else -> resources.getQuantityString(
                R.plurals.accessibility_services_on,
                enabledServices.split(":").size,
                enabledServices.split(":").size
            )
        }

        accessibilityTwoLineButton.setSpan(
            SpannableString(resources.getString(R.string.accessibility_status, status))
        )

        accessibilityTwoLineButton.setOnClickListener {
            startActivity(ACCESSIBILITY_SETTINGS)
        }
    }

    private fun setPanelShortcutButton() {
        val enabled = sharedPreferences.panelShortcutEnabled
        val statusSpan = SpannableString(
            resources.getString(
                R.string.panel_shortcut_status,
                resources.getString(if (enabled) R.string.enabled else R.string.disabled)
            )
        )
        panelShortcutTwoLineButton.setSpan(statusSpan)

        panelShortcutTwoLineButton.setOnClickListener {
            sharedPreferences.panelShortcutEnabled = !sharedPreferences.panelShortcutEnabled
            setPanelShortcutButton()
        }
    }

    private fun setBluetoothButton() {
        var btString = resources.getString(R.string.disabled)
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
            btString = resources.getString(R.string.enabled)
        }

        val btSpan = SpannableString(resources.getString(R.string.bluetooth_status, btString))
        bluetoothTwoLineButton.setSpan(btSpan)

        bluetoothTwoLineButton.setOnClickListener {
            startActivity(BLUETOOTH_SETTINGS)
        }
    }

    override fun onItemClick(view: NotificationItemView) {
        val notification = view.statusBarNotification?.notification ?: return
        try {
            if (notification.contentIntent != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val activityOptions = ActivityOptions.makeBasic()
                    activityOptions.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    notification.contentIntent?.send(activityOptions.toBundle())
                } else {
                    notification.contentIntent?.send()
                }
            }

            view.statusBarNotification?.let {
                if (NotificationUtils.shouldAutoCancel(it.notification)) {
                    notificationViewModel.cancelNotification(it.key)
                }
            }
        } catch (e: PendingIntent.CanceledException) {
            Log.d(
                "SystemOptionsActivity",
                "Pending intent canceled for : ${notification.contentIntent}"
            )
        }
    }

    override fun onKey(view: NotificationItemView, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }

        if (view.statusBarNotification?.isClearable == false) {
            return false
        }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (view.swipeStatus == NotificationItemView.SwipeStatus.LEFT) {
                    view.resetState()
                    view.statusBarNotification?.let { notificationViewModel.cancelNotification(it.key) }
                } else {
                    view.animateDismissLeft()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (view.swipeStatus == NotificationItemView.SwipeStatus.RIGHT) {
                    view.resetState()
                    view.statusBarNotification?.let { notificationViewModel.cancelNotification(it.key) }
                } else {
                    view.animateDismissRight()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (view.swipeStatus != NotificationItemView.SwipeStatus.NONE) {
                    view.resetState()
                    view.statusBarNotification?.let { notificationViewModel.cancelNotification(it.key) }
                    return true
                }
                return false
            }

            else -> {
                if (view.swipeStatus != NotificationItemView.SwipeStatus.NONE) {
                    view.animateCloseDismiss()
                }
                return false
            }
        }
    }

    companion object {
        private const val LOG_TAG = "SystemOptions"

        val SETTINGS: Intent = Intent(Settings.ACTION_SETTINGS)
        val WIFI_SETTINGS: Intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        val BLUETOOTH_SETTINGS: Intent = Intent().apply {
            setClassName("com.android.tv.settings", "com.android.tv.settings.slice.SliceActivity")
            putExtra(
                "slice_uri",
                "content://com.android.tv.settings.accessories.sliceprovider/general"
            )
        }
        val NOTIFICATION_SETTINGS: Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        val ACCESSIBILITY_SETTINGS: Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        /** Not a public constant, and ACTION_DREAM_SETTINGS resolves to nothing on TV. */
        const val SCREENSAVER_COMPONENTS = "screensaver_components"

        /** DisplaySoundActivity advertises this action, so no explicit name needed. */
        val SOUND_SETTINGS: Intent = Intent("com.android.settings.SOUND_SETTINGS")

        /**
         * Starts the selected dream. TvSettings' DaydreamVoiceAction is
         * exported for this action and calls DreamBackend.startDreaming(),
         * so the launcher does not need WRITE_DREAM_STATE itself.
         */
        val SCREENSAVER_START: Intent = Intent("com.google.android.pano.action.SLEEP")

        /** Exported, but with no intent filter, so it needs the explicit name. */
        val SCREENSAVER_SETTINGS: Intent = Intent().apply {
            setClassName(
                "com.android.tv.settings",
                "com.android.tv.settings.device.display.daydream.DaydreamActivity"
            )
        }

        val wifiIcons = intArrayOf(
            R.drawable.ic_wifi_signal_0,
            R.drawable.ic_wifi_signal_1,
            R.drawable.ic_wifi_signal_2,
            R.drawable.ic_wifi_signal_3,
            R.drawable.ic_wifi_signal_4
        )
    }
}
