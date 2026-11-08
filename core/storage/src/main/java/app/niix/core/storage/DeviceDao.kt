package app.niix.core.storage

import android.content.ContentValues
import app.niix.core.model.Device
import app.niix.core.model.DeviceList
import app.niix.core.model.OnionAddress
import net.zetetic.database.sqlcipher.SQLiteDatabase

class DeviceDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.Devices
    private val lists = Schema.DeviceLists

    /** The account's stored list, or null if it never sent one. */
    fun list(account: String): DeviceList? {
        val epoch = db.rawQuery(
            "SELECT ${lists.COL_EPOCH} FROM ${lists.TABLE} WHERE ${lists.COL_ACCOUNT} = ?",
            arrayOf(account),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else return null }
        val devices = db.rawQuery(
            "SELECT ${t.COL_DEVICE_ID}, ${t.COL_ONION} FROM ${t.TABLE} WHERE ${t.COL_ACCOUNT} = ? ORDER BY ${t.COL_DEVICE_ID}",
            arrayOf(account),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Device(c.getInt(0), OnionAddress.parse(c.getString(1)))) }
        }
        return runCatching { DeviceList(epoch, devices) }.getOrNull()
    }

    /** The account and device an address belongs to, if it's a known device. */
    fun owner(onion: String): Pair<String, Int>? =
        db.rawQuery(
            "SELECT ${t.COL_ACCOUNT}, ${t.COL_DEVICE_ID} FROM ${t.TABLE} WHERE ${t.COL_ONION} = ?",
            arrayOf(onion),
        ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getInt(1) else null }

    /**
     * Stores [list] for [account] if it's newer than what's held. Returns whether it was stored.
     * An address can only belong to one account, so a list naming another account's device is
     * refused rather than letting one account take over another's address.
     */
    fun replaceIfNewer(account: String, list: DeviceList): Boolean = secureDatabase.inTransaction {
        val current = list(account)
        if (current != null && list.epoch <= current.epoch) return@inTransaction false
        val claimed = list.devices.map { it.onion.value }
        if (claimed.any { onion -> owner(onion)?.first.let { it != null && it != account } }) {
            return@inTransaction false
        }
        db.delete(t.TABLE, "${t.COL_ACCOUNT} = ?", arrayOf(account))
        list.devices.forEach { device ->
            db.insert(t.TABLE, null, ContentValues().apply {
                put(t.COL_ACCOUNT, account)
                put(t.COL_DEVICE_ID, device.id)
                put(t.COL_ONION, device.onion.value)
            })
        }
        db.insertWithOnConflict(
            lists.TABLE,
            null,
            ContentValues().apply {
                put(lists.COL_ACCOUNT, account)
                put(lists.COL_EPOCH, list.epoch)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        true
    }

    fun forget(account: String) = secureDatabase.inTransaction {
        db.delete(t.TABLE, "${t.COL_ACCOUNT} = ?", arrayOf(account))
        db.delete(lists.TABLE, "${lists.COL_ACCOUNT} = ?", arrayOf(account))
    }
}
