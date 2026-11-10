package app.niix.core.model

/** One device of an account: its number within the account and the address it's reached at. */
data class Device(val id: Int, val onion: OnionAddress)

/**
 * The devices an account is on. [epoch] only goes up, so an old list arriving late can't bring back
 * a device that was removed.
 */
data class DeviceList(val epoch: Long, val devices: List<Device>) {

    init {
        require(epoch >= 1) { "Device list epoch must be positive" }
        require(devices.size in 1..MAX_DEVICES) { "A device list has 1 to $MAX_DEVICES devices" }
        require(devices.all { it.id in 1..MAX_DEVICE_ID }) { "Device id out of range" }
        require(devices.map { it.id }.toSet().size == devices.size) { "Duplicate device id" }
        require(devices.map { it.onion }.toSet().size == devices.size) { "Duplicate device address" }
    }

    fun device(id: Int): Device? = devices.firstOrNull { it.id == id }

    fun nextFreeId(): Int = (1..MAX_DEVICE_ID).first { id -> devices.none { it.id == id } }

    companion object {
        /** Each device multiplies every message sent to the account, so the number is kept small. */
        const val MAX_DEVICES = 5
        const val MAX_DEVICE_ID = 127

        /** What an account that has never sent a list is assumed to be: one device, at its address. */
        fun single(account: OnionAddress) = DeviceList(1, listOf(Device(1, account)))
    }
}
