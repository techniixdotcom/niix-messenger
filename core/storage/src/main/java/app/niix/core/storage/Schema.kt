package app.niix.core.storage

object Schema {
    const val DATABASE_FILENAME = "niix.db"
    const val DATABASE_VERSION = 1

    object Account {
        const val TABLE = "account"
        const val COL_ID = "id"
        const val COL_IDENTITY_KEYPAIR = "identity_keypair"
        const val COL_REGISTRATION_ID = "registration_id"
        const val SINGLETON_ID = 1
    }

    object PreKeys {
        const val TABLE = "prekeys"
        const val COL_ID = "prekey_id"
        const val COL_RECORD = "record"
    }

    object SignedPreKeys {
        const val TABLE = "signed_prekeys"
        const val COL_ID = "signed_prekey_id"
        const val COL_RECORD = "record"
    }

    object KyberPreKeys {
        const val TABLE = "kyber_prekeys"
        const val COL_ID = "kyber_prekey_id"
        const val COL_RECORD = "record"
        const val COL_LAST_RESORT = "last_resort"
    }

    object KyberUsedBaseKeys {
        const val TABLE = "kyber_used_base_keys"
        const val COL_KYBER_ID = "kyber_prekey_id"
        const val COL_SIGNED_ID = "signed_prekey_id"
        const val COL_BASE_KEY = "base_key"
    }

    object Sessions {
        const val TABLE = "sessions"
        const val COL_NAME = "name"
        const val COL_DEVICE_ID = "device_id"
        const val COL_RECORD = "record"
    }

    object Identities {
        const val TABLE = "identities"
        const val COL_NAME = "name"
        const val COL_IDENTITY_KEY = "identity_key"
        const val COL_TRUST_STATE = "trust_state"
        const val COL_FIRST_SEEN = "first_seen"
    }

    object Contacts {
        const val TABLE = "contacts"
        const val COL_ONION = "onion_address"
        const val COL_DISPLAY_NAME = "display_name"
        const val COL_FINGERPRINT = "fingerprint"
        const val COL_TRUST_STATE = "trust_state"
        const val COL_ADDED_AT = "added_at"
    }

    object Conversations {
        const val TABLE = "conversations"
        const val COL_ID = "id"
        const val COL_TYPE = "type"
        const val COL_TITLE = "title"
        const val COL_DISAPPEAR_SECONDS = "disappear_seconds"
        const val COL_CREATED_AT = "created_at"
    }

    object GroupMembers {
        const val TABLE = "group_members"
        const val COL_CONVERSATION_ID = "conversation_id"
        const val COL_MEMBER_ONION = "member_onion"
        const val COL_ROLE = "role"
    }

    object Messages {
        const val TABLE = "messages"
        const val COL_ID = "id"
        const val COL_CONVERSATION_ID = "conversation_id"
        const val COL_SENDER_ONION = "sender_onion"
        const val COL_DIRECTION = "direction"
        const val COL_TYPE = "type"
        const val COL_BODY = "body"
        const val COL_ATTACHMENT_ID = "attachment_id"
        const val COL_CREATED_AT = "created_at"
        const val COL_EXPIRES_AT = "expires_at"
        const val COL_DELIVERY_STATE = "delivery_state"
        const val COL_DELETED = "deleted"
        const val COL_REMOTE_DELETABLE = "remote_deletable"
    }

    object Attachments {
        const val TABLE = "attachments"
        const val COL_ID = "id"
        const val COL_CONVERSATION_ID = "conversation_id"
        const val COL_FILE_PATH = "file_path"
        const val COL_MIME_TYPE = "mime_type"
        const val COL_SIZE_BYTES = "size_bytes"
        const val COL_ENC_KEY = "enc_key"
        const val COL_DIGEST = "digest"
        const val COL_STATE = "state"
        const val COL_CREATED_AT = "created_at"
    }

    object Settings {
        const val TABLE = "settings"
        const val COL_KEY = "key"
        const val COL_VALUE = "value"
    }

    object Blocked {
        const val TABLE = "blocked"
        const val COL_ONION = "onion_address"
    }

    val DDL: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS ${Account.TABLE} (
            ${Account.COL_ID} INTEGER PRIMARY KEY,
            ${Account.COL_IDENTITY_KEYPAIR} BLOB NOT NULL,
            ${Account.COL_REGISTRATION_ID} INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${PreKeys.TABLE} (
            ${PreKeys.COL_ID} INTEGER PRIMARY KEY,
            ${PreKeys.COL_RECORD} BLOB NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${SignedPreKeys.TABLE} (
            ${SignedPreKeys.COL_ID} INTEGER PRIMARY KEY,
            ${SignedPreKeys.COL_RECORD} BLOB NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${KyberPreKeys.TABLE} (
            ${KyberPreKeys.COL_ID} INTEGER PRIMARY KEY,
            ${KyberPreKeys.COL_RECORD} BLOB NOT NULL,
            ${KyberPreKeys.COL_LAST_RESORT} INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${KyberUsedBaseKeys.TABLE} (
            ${KyberUsedBaseKeys.COL_KYBER_ID} INTEGER NOT NULL,
            ${KyberUsedBaseKeys.COL_SIGNED_ID} INTEGER NOT NULL,
            ${KyberUsedBaseKeys.COL_BASE_KEY} BLOB NOT NULL,
            PRIMARY KEY (${KyberUsedBaseKeys.COL_KYBER_ID}, ${KyberUsedBaseKeys.COL_SIGNED_ID}, ${KyberUsedBaseKeys.COL_BASE_KEY})
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Sessions.TABLE} (
            ${Sessions.COL_NAME} TEXT NOT NULL,
            ${Sessions.COL_DEVICE_ID} INTEGER NOT NULL,
            ${Sessions.COL_RECORD} BLOB NOT NULL,
            PRIMARY KEY (${Sessions.COL_NAME}, ${Sessions.COL_DEVICE_ID})
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Identities.TABLE} (
            ${Identities.COL_NAME} TEXT PRIMARY KEY,
            ${Identities.COL_IDENTITY_KEY} BLOB NOT NULL,
            ${Identities.COL_TRUST_STATE} TEXT NOT NULL,
            ${Identities.COL_FIRST_SEEN} INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Contacts.TABLE} (
            ${Contacts.COL_ONION} TEXT PRIMARY KEY,
            ${Contacts.COL_DISPLAY_NAME} TEXT NOT NULL,
            ${Contacts.COL_FINGERPRINT} TEXT NOT NULL,
            ${Contacts.COL_TRUST_STATE} TEXT NOT NULL,
            ${Contacts.COL_ADDED_AT} INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Conversations.TABLE} (
            ${Conversations.COL_ID} TEXT PRIMARY KEY,
            ${Conversations.COL_TYPE} TEXT NOT NULL,
            ${Conversations.COL_TITLE} TEXT NOT NULL,
            ${Conversations.COL_DISAPPEAR_SECONDS} INTEGER NOT NULL DEFAULT 0,
            ${Conversations.COL_CREATED_AT} INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${GroupMembers.TABLE} (
            ${GroupMembers.COL_CONVERSATION_ID} TEXT NOT NULL,
            ${GroupMembers.COL_MEMBER_ONION} TEXT NOT NULL,
            ${GroupMembers.COL_ROLE} TEXT NOT NULL,
            PRIMARY KEY (${GroupMembers.COL_CONVERSATION_ID}, ${GroupMembers.COL_MEMBER_ONION})
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Messages.TABLE} (
            ${Messages.COL_ID} TEXT PRIMARY KEY,
            ${Messages.COL_CONVERSATION_ID} TEXT NOT NULL,
            ${Messages.COL_SENDER_ONION} TEXT NOT NULL,
            ${Messages.COL_DIRECTION} TEXT NOT NULL,
            ${Messages.COL_TYPE} TEXT NOT NULL,
            ${Messages.COL_BODY} TEXT NOT NULL DEFAULT '',
            ${Messages.COL_ATTACHMENT_ID} TEXT,
            ${Messages.COL_CREATED_AT} INTEGER NOT NULL,
            ${Messages.COL_EXPIRES_AT} INTEGER,
            ${Messages.COL_DELIVERY_STATE} TEXT NOT NULL,
            ${Messages.COL_DELETED} INTEGER NOT NULL DEFAULT 0,
            ${Messages.COL_REMOTE_DELETABLE} INTEGER NOT NULL DEFAULT 1
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_messages_conv ON ${Messages.TABLE} (${Messages.COL_CONVERSATION_ID}, ${Messages.COL_CREATED_AT})",
        "CREATE INDEX IF NOT EXISTS idx_messages_expiry ON ${Messages.TABLE} (${Messages.COL_EXPIRES_AT})",
        """
        CREATE TABLE IF NOT EXISTS ${Attachments.TABLE} (
            ${Attachments.COL_ID} TEXT PRIMARY KEY,
            ${Attachments.COL_CONVERSATION_ID} TEXT NOT NULL,
            ${Attachments.COL_FILE_PATH} TEXT NOT NULL,
            ${Attachments.COL_MIME_TYPE} TEXT NOT NULL,
            ${Attachments.COL_SIZE_BYTES} INTEGER NOT NULL,
            ${Attachments.COL_ENC_KEY} BLOB NOT NULL,
            ${Attachments.COL_DIGEST} BLOB,
            ${Attachments.COL_STATE} TEXT NOT NULL,
            ${Attachments.COL_CREATED_AT} INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Settings.TABLE} (
            ${Settings.COL_KEY} TEXT PRIMARY KEY,
            ${Settings.COL_VALUE} TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS ${Blocked.TABLE} (
            ${Blocked.COL_ONION} TEXT PRIMARY KEY
        )
        """.trimIndent(),
    )
}
