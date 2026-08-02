package app.niix.core.storage

import android.content.ContentValues
import app.niix.core.model.GroupMember
import app.niix.core.model.GroupRole
import app.niix.core.model.OnionAddress
import net.zetetic.database.sqlcipher.SQLiteDatabase

class MemberDao internal constructor(private val secureDatabase: SecureDatabase) {

    private val db: SQLiteDatabase get() = secureDatabase.open()
    private val t = Schema.GroupMembers

    fun add(member: GroupMember) {
        val values = ContentValues().apply {
            put(t.COL_CONVERSATION_ID, member.conversationId)
            put(t.COL_MEMBER_ONION, member.memberOnion.value)
            put(t.COL_ROLE, member.role.name)
        }
        db.insertWithOnConflict(t.TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun remove(conversationId: String, memberOnion: OnionAddress) {
        db.delete(
            t.TABLE,
            "${t.COL_CONVERSATION_ID} = ? AND ${t.COL_MEMBER_ONION} = ?",
            arrayOf(conversationId, memberOnion.value),
        )
    }

    fun isAdmin(conversationId: String, onion: String): Boolean {
        db.rawQuery(
            "SELECT ${t.COL_ROLE} FROM ${t.TABLE} WHERE ${t.COL_CONVERSATION_ID} = ? AND ${t.COL_MEMBER_ONION} = ?",
            arrayOf(conversationId, onion),
        ).use { c -> if (c.moveToNext()) return c.getString(0) == GroupRole.ADMIN.name }
        return false
    }

    fun replaceAll(conversationId: String, members: List<String>, admins: List<String>) {
        db.delete(t.TABLE, "${t.COL_CONVERSATION_ID} = ?", arrayOf(conversationId))
        members.distinct().forEach { onion ->
            val role = if (onion in admins) GroupRole.ADMIN else GroupRole.MEMBER
            add(GroupMember(conversationId, OnionAddress.parse(onion), role))
        }
    }

    fun setRole(conversationId: String, onion: String, role: GroupRole) {
        add(GroupMember(conversationId, OnionAddress.parse(onion), role))
    }

    fun listForConversation(conversationId: String): List<GroupMember> {
        val result = mutableListOf<GroupMember>()
        db.rawQuery(
            "SELECT * FROM ${t.TABLE} WHERE ${t.COL_CONVERSATION_ID} = ?",
            arrayOf(conversationId),
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    GroupMember(
                        conversationId = c.getString(c.getColumnIndexOrThrow(t.COL_CONVERSATION_ID)),
                        memberOnion = OnionAddress.parse(c.getString(c.getColumnIndexOrThrow(t.COL_MEMBER_ONION))),
                        role = GroupRole.valueOf(c.getString(c.getColumnIndexOrThrow(t.COL_ROLE))),
                    ),
                )
            }
        }
        return result
    }
}
