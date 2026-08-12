package io.github.miuzarte.scrcpyforandroid.services

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat

object BluetoothHidKeyboard {

    private const val TAG = "BooxHidKeyboard"

    /*
     * Prefer a bonded device whose Bluetooth name contains this.
     * If none matches, we fall back to the first bonded PHONE.
     */
    private const val PREFERRED_TARGET_HINT = "S26"

    private const val REPORT_ID_KEYBOARD = 1

    private var appContext: Context? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var targetDevice: BluetoothDevice? = null

    private var appRegistered = false
    private var connected = false

    private var modifierBits = 0
    private val pressedKeys = LinkedHashSet<Int>()

    /*
     * Standard USB HID boot-keyboard-style descriptor.
     *
     * Report:
     *   byte 0 = modifier bits
     *   byte 1 = reserved
     *   byte 2-7 = up to six simultaneous keys
     */
    private val keyboardDescriptor = bytes(
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)

        0x85, 0x01,       // Report ID 1

        0x05, 0x07,       // Usage Page (Keyboard)
        0x19, 0xE0,       // Usage Minimum (Left Control)
        0x29, 0xE7,       // Usage Maximum (Right GUI)
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95, 0x08,
        0x81, 0x02,       // Input: modifier byte

        0x95, 0x01,
        0x75, 0x08,
        0x81, 0x01,       // Reserved byte

        0x95, 0x06,
        0x75, 0x08,
        0x15, 0x00,
        0x25, 0x65,
        0x05, 0x07,
        0x19, 0x00,
        0x29, 0x65,
        0x81, 0x00,       // Six key usages

        0xC0              // End Collection
    )

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }

    fun hasPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "Bluetooth HID Device requires Android 9+")
            return
        }

        if (!hasPermission(context)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted")
            return
        }

        appContext = context.applicationContext

        val manager =
            context.getSystemService(BluetoothManager::class.java)

        val adapter = manager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is unavailable or disabled")
            return
        }

        targetDevice = chooseTarget(adapter)

        Log.i(
            TAG,
            "Selected HID target: ${targetDevice?.name ?: "none"}",
        )

        /*
         * If we already have the profile proxy, just reconnect.
         * StreamActivity can be recreated during fullscreen/PIP changes.
         */
        hidDevice?.let { hid ->
            if (appRegistered) {
                targetDevice?.let { target ->
                    connected =
                        hid.getConnectionState(target) ==
                            BluetoothProfile.STATE_CONNECTED

                    if (!connected) {
                        Log.i(TAG, "Connecting HID keyboard to ${target.name}")
                        hid.connect(target)
                    }
                }
            }
            return
        }

        adapter.getProfileProxy(
            context.applicationContext,
            profileListener,
            BluetoothProfile.HID_DEVICE,
        )
    }

    @SuppressLint("MissingPermission")
    private fun chooseTarget(
        adapter: BluetoothAdapter,
    ): BluetoothDevice? {

        val bonded = adapter.bondedDevices.toList()

        bonded.forEach { device ->
            Log.i(
                TAG,
                "Bonded Bluetooth device: ${device.name} / ${device.address}",
            )
        }

        /*
         * First preference: something explicitly named S26.
         */
bonded.firstOrNull { device ->
    device.name?.contains(PREFERRED_TARGET_HINT, ignoreCase = true) == true
}?.let {
    return it
}
        /*
         * Second preference: any bonded phone.
         */
        bonded.firstOrNull { device ->
            device.bluetoothClass?.majorDeviceClass ==
                BluetoothClass.Device.Major.PHONE
        }?.let {
            return it
        }

        /*
         * If exactly one device is bonded, it is probably our S26.
         */
        return bonded.singleOrNull()
    }

    private val profileListener =
        object : BluetoothProfile.ServiceListener {

            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                if (profile != BluetoothProfile.HID_DEVICE) return

                hidDevice = proxy as BluetoothHidDevice

                Log.i(TAG, "Bluetooth HID profile connected")

                registerKeyboard()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return

                Log.i(TAG, "Bluetooth HID profile disconnected")

                hidDevice = null
                appRegistered = false
                connected = false
            }
        }

    private val hidCallback =
        object : BluetoothHidDevice.Callback() {

            @SuppressLint("MissingPermission")
            override fun onAppStatusChanged(
                pluggedDevice: BluetoothDevice?,
                registered: Boolean,
            ) {
                appRegistered = registered

                Log.i(
                    TAG,
                    "HID registration changed: registered=$registered",
                )

                if (!registered) {
                    connected = false
                    return
                }

                /*
                 * Android may provide an existing host here.
                 * Otherwise use the S26 we found in bonded devices.
                 */
                if (pluggedDevice != null) {
                    targetDevice = pluggedDevice
                }

                val target = targetDevice

                if (target != null) {
                    Log.i(
                        TAG,
                        "Requesting HID connection to ${target.name}",
                    )
                    hidDevice?.connect(target)
                } else {
                    Log.w(TAG, "No paired S26 found for HID")
                }
            }

            override fun onConnectionStateChanged(
                device: BluetoothDevice?,
                state: Int,
            ) {
                connected = state == BluetoothProfile.STATE_CONNECTED

                if (connected && device != null) {
                    targetDevice = device
                    Log.i(TAG, "HID keyboard connected")
                } else {
                    Log.i(TAG, "HID state=$state")
                }
            }
        }

    @SuppressLint("MissingPermission")
    private fun registerKeyboard() {
        val context = appContext ?: return
        val hid = hidDevice ?: return

        if (appRegistered) return

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BOOX Remote Keyboard",
            "BOOX folio keyboard forwarding",
            "ScrcpyForAndroid",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            keyboardDescriptor,
        )

        val accepted = hid.registerApp(
            sdp,
            null,
            null,
            ContextCompat.getMainExecutor(context),
            hidCallback,
        )

        Log.i(TAG, "registerApp accepted=$accepted")
    }

    /*
     * Returns true when this event was handled by Bluetooth HID.
     * Returns false when we are not connected, so StreamActivity
     * can fall back to the existing scrcpy injection path.
     */
    @SuppressLint("MissingPermission")
    fun handleKeyEvent(event: KeyEvent): Boolean {

        if (!connected || !appRegistered) {
            return false
        }

        val usage = androidKeyCodeToHidUsage(event.keyCode)

        if (usage == 0) {
            return false
        }

        when (event.action) {

            KeyEvent.ACTION_DOWN -> {

                if (usage in 0xE0..0xE7) {
                    modifierBits =
                        modifierBits or (1 shl (usage - 0xE0))
                } else {
                    /*
                     * A standard keyboard report carries six
                     * simultaneous non-modifier keys.
                     */
                    if (pressedKeys.size < 6 ||
                        pressedKeys.contains(usage)
                    ) {
                        pressedKeys.add(usage)
                    }
                }

                sendKeyboardReport()
                return true
            }

            KeyEvent.ACTION_UP -> {

                if (usage in 0xE0..0xE7) {
                    modifierBits =
                        modifierBits and
                            (1 shl (usage - 0xE0)).inv()
                } else {
                    pressedKeys.remove(usage)
                }

                sendKeyboardReport()
                return true
            }
        }

        return false
    }

    @SuppressLint("MissingPermission")
    private fun sendKeyboardReport() {

        val hid = hidDevice ?: return
        val target = targetDevice ?: return

        if (!connected) return

        val report = ByteArray(8)

        report[0] = modifierBits.toByte()
        report[1] = 0

        pressedKeys
            .take(6)
            .forEachIndexed { index, usage ->
                report[index + 2] = usage.toByte()
            }

        hid.sendReport(
            target,
            REPORT_ID_KEYBOARD,
            report,
        )
    }

    private fun androidKeyCodeToHidUsage(keyCode: Int): Int =
        when (keyCode) {

            KeyEvent.KEYCODE_A -> 0x04
            KeyEvent.KEYCODE_B -> 0x05
            KeyEvent.KEYCODE_C -> 0x06
            KeyEvent.KEYCODE_D -> 0x07
            KeyEvent.KEYCODE_E -> 0x08
            KeyEvent.KEYCODE_F -> 0x09
            KeyEvent.KEYCODE_G -> 0x0A
            KeyEvent.KEYCODE_H -> 0x0B
            KeyEvent.KEYCODE_I -> 0x0C
            KeyEvent.KEYCODE_J -> 0x0D
            KeyEvent.KEYCODE_K -> 0x0E
            KeyEvent.KEYCODE_L -> 0x0F
            KeyEvent.KEYCODE_M -> 0x10
            KeyEvent.KEYCODE_N -> 0x11
            KeyEvent.KEYCODE_O -> 0x12
            KeyEvent.KEYCODE_P -> 0x13
            KeyEvent.KEYCODE_Q -> 0x14
            KeyEvent.KEYCODE_R -> 0x15
            KeyEvent.KEYCODE_S -> 0x16
            KeyEvent.KEYCODE_T -> 0x17
            KeyEvent.KEYCODE_U -> 0x18
            KeyEvent.KEYCODE_V -> 0x19
            KeyEvent.KEYCODE_W -> 0x1A
            KeyEvent.KEYCODE_X -> 0x1B
            KeyEvent.KEYCODE_Y -> 0x1C
            KeyEvent.KEYCODE_Z -> 0x1D

            KeyEvent.KEYCODE_1 -> 0x1E
            KeyEvent.KEYCODE_2 -> 0x1F
            KeyEvent.KEYCODE_3 -> 0x20
            KeyEvent.KEYCODE_4 -> 0x21
            KeyEvent.KEYCODE_5 -> 0x22
            KeyEvent.KEYCODE_6 -> 0x23
            KeyEvent.KEYCODE_7 -> 0x24
            KeyEvent.KEYCODE_8 -> 0x25
            KeyEvent.KEYCODE_9 -> 0x26
            KeyEvent.KEYCODE_0 -> 0x27

            KeyEvent.KEYCODE_ENTER -> 0x28
            KeyEvent.KEYCODE_ESCAPE -> 0x29
            KeyEvent.KEYCODE_DEL -> 0x2A
            KeyEvent.KEYCODE_TAB -> 0x2B
            KeyEvent.KEYCODE_SPACE -> 0x2C
            KeyEvent.KEYCODE_MINUS -> 0x2D
            KeyEvent.KEYCODE_EQUALS -> 0x2E
            KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
            KeyEvent.KEYCODE_BACKSLASH -> 0x31
            KeyEvent.KEYCODE_SEMICOLON -> 0x33
            KeyEvent.KEYCODE_APOSTROPHE -> 0x34
            KeyEvent.KEYCODE_GRAVE -> 0x35
            KeyEvent.KEYCODE_COMMA -> 0x36
            KeyEvent.KEYCODE_PERIOD -> 0x37
            KeyEvent.KEYCODE_SLASH -> 0x38
            KeyEvent.KEYCODE_CAPS_LOCK -> 0x39

            KeyEvent.KEYCODE_F1 -> 0x3A
            KeyEvent.KEYCODE_F2 -> 0x3B
            KeyEvent.KEYCODE_F3 -> 0x3C
            KeyEvent.KEYCODE_F4 -> 0x3D
            KeyEvent.KEYCODE_F5 -> 0x3E
            KeyEvent.KEYCODE_F6 -> 0x3F
            KeyEvent.KEYCODE_F7 -> 0x40
            KeyEvent.KEYCODE_F8 -> 0x41
            KeyEvent.KEYCODE_F9 -> 0x42
            KeyEvent.KEYCODE_F10 -> 0x43
            KeyEvent.KEYCODE_F11 -> 0x44
            KeyEvent.KEYCODE_F12 -> 0x45

            KeyEvent.KEYCODE_INSERT -> 0x49
            KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
            KeyEvent.KEYCODE_PAGE_UP -> 0x4B
            KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
            KeyEvent.KEYCODE_MOVE_END -> 0x4D
            KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E

            KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
            KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
            KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
            KeyEvent.KEYCODE_DPAD_UP -> 0x52

            KeyEvent.KEYCODE_NUM_LOCK -> 0x53
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x54
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x55
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x56
            KeyEvent.KEYCODE_NUMPAD_ADD -> 0x57
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x58
            KeyEvent.KEYCODE_NUMPAD_1 -> 0x59
            KeyEvent.KEYCODE_NUMPAD_2 -> 0x5A
            KeyEvent.KEYCODE_NUMPAD_3 -> 0x5B
            KeyEvent.KEYCODE_NUMPAD_4 -> 0x5C
            KeyEvent.KEYCODE_NUMPAD_5 -> 0x5D
            KeyEvent.KEYCODE_NUMPAD_6 -> 0x5E
            KeyEvent.KEYCODE_NUMPAD_7 -> 0x5F
            KeyEvent.KEYCODE_NUMPAD_8 -> 0x60
            KeyEvent.KEYCODE_NUMPAD_9 -> 0x61
            KeyEvent.KEYCODE_NUMPAD_0 -> 0x62
            KeyEvent.KEYCODE_NUMPAD_DOT -> 0x63

            KeyEvent.KEYCODE_CTRL_LEFT -> 0xE0
            KeyEvent.KEYCODE_SHIFT_LEFT -> 0xE1
            KeyEvent.KEYCODE_ALT_LEFT -> 0xE2
            KeyEvent.KEYCODE_META_LEFT -> 0xE3

            KeyEvent.KEYCODE_CTRL_RIGHT -> 0xE4
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xE5
            KeyEvent.KEYCODE_ALT_RIGHT -> 0xE6
            KeyEvent.KEYCODE_META_RIGHT -> 0xE7

            else -> 0
        }
}
