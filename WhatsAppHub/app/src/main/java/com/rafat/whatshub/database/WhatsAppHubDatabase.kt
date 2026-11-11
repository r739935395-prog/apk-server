package com.rafat.whatshub.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import com.rafat.whatshub.security.KeystoreKeyManager
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Encrypted SQLite database manager implementing all 10 domain tables:
 *   1. accounts
 *   2. sessions
 *   3. contacts
 *   4. chats
 *   5. messages
 *   6. message_media
 *   7. groups
 *   8. group_members
 *   9. synchronization_state
 *  10. app_settings
 *
 * Enforces composite primary keys:
 *   - Chat identity:    (account_id, chat_jid)
 *   - Message identity: (account_id, chat_jid, message_id)
 *
 * Uses parameterized SQL bindings exclusively to prevent injection and cross-account leakage.
 */
class WhatsAppHubDatabase(
    context: Context,
    dbName: String? = DB_FILE_NAME,
    factory: SupportSQLiteOpenHelper.Factory
) {
    private val openHelper: SupportSQLiteOpenHelper

    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    init {
        val callback = object : SupportSQLiteOpenHelper.Callback(DB_VERSION) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                createSchema(db)
                seedDefaultAccounts(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                createSchema(db)
                seedDefaultAccounts(db)
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                SanitizedLogger.error("DB", "Database corruption detected on ${db.path}; isolating corrupted file")
                val path = db.path
                try {
                    db.close()
                } catch (_: Throwable) {
                }
                if (!path.isNullOrBlank()) {
                    val f = File(path)
                    if (f.exists()) {
                        f.renameTo(File("${path}.corrupt.${System.currentTimeMillis()}"))
                    }
                }
            }
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        openHelper = factory.create(config)
        // Force schema initialization & seed verification
        openHelper.writableDatabase
    }

    private val db: SupportSQLiteDatabase
        get() = openHelper.writableDatabase

    private fun notifyChanged() {
        _changeVersion.value = _changeVersion.value + 1L
    }

    private fun requireValidAccount(accountId: Int) {
        require(accountId in 1..4) { "Account ID out of bounds: $accountId (must be 1..4)" }
    }

    private fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                account_id INTEGER PRIMARY KEY CHECK (account_id BETWEEN 1 AND 4),
                label TEXT NOT NULL,
                color_hex INTEGER NOT NULL,
                jid TEXT NOT NULL DEFAULT '',
                phone TEXT NOT NULL DEFAULT '',
                push_name TEXT NOT NULL DEFAULT '',
                state TEXT NOT NULL DEFAULT 'not_connected',
                error_message TEXT NOT NULL DEFAULT '',
                reconnect_attempt INTEGER NOT NULL DEFAULT 0,
                has_saved_session INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                account_id INTEGER PRIMARY KEY CHECK (account_id BETWEEN 1 AND 4),
                device_jid TEXT NOT NULL DEFAULT '',
                key_alias TEXT NOT NULL,
                paired_at_ms INTEGER NOT NULL DEFAULT 0,
                last_connected_at_ms INTEGER NOT NULL DEFAULT 0,
                session_dir TEXT NOT NULL,
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                contact_jid TEXT NOT NULL,
                full_name TEXT NOT NULL DEFAULT '',
                first_name TEXT NOT NULL DEFAULT '',
                push_name TEXT NOT NULL DEFAULT '',
                business_name TEXT NOT NULL DEFAULT '',
                is_online INTEGER NOT NULL DEFAULT 0,
                last_seen_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (account_id, contact_jid),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chats (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                chat_jid TEXT NOT NULL,
                composite_key TEXT NOT NULL UNIQUE,
                title TEXT NOT NULL,
                is_group INTEGER NOT NULL DEFAULT 0,
                last_message_id TEXT NOT NULL DEFAULT '',
                last_message_preview TEXT NOT NULL DEFAULT '',
                last_message_ts_ms INTEGER NOT NULL DEFAULT 0,
                last_sender_name TEXT NOT NULL DEFAULT '',
                unread_count INTEGER NOT NULL DEFAULT 0,
                is_typing INTEGER NOT NULL DEFAULT 0,
                typing_sender TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (account_id, chat_jid),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_latest ON chats(last_message_ts_ms DESC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                chat_jid TEXT NOT NULL,
                message_id TEXT NOT NULL,
                composite_key TEXT NOT NULL UNIQUE,
                sender_jid TEXT NOT NULL DEFAULT '',
                sender_name TEXT NOT NULL DEFAULT '',
                is_from_me INTEGER NOT NULL DEFAULT 0,
                is_group INTEGER NOT NULL DEFAULT 0,
                text_body TEXT NOT NULL DEFAULT '',
                media_type TEXT NOT NULL DEFAULT '',
                quoted_message_id TEXT NOT NULL DEFAULT '',
                quoted_sender_jid TEXT NOT NULL DEFAULT '',
                quoted_text TEXT NOT NULL DEFAULT '',
                reactions_summary TEXT NOT NULL DEFAULT '',
                is_deleted INTEGER NOT NULL DEFAULT 0,
                is_edited INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'delivered',
                timestamp_ms INTEGER NOT NULL,
                PRIMARY KEY (account_id, chat_jid, message_id),
                FOREIGN KEY (account_id, chat_jid) REFERENCES chats(account_id, chat_jid) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON messages(account_id, chat_jid, timestamp_ms ASC)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_media (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                chat_jid TEXT NOT NULL,
                message_id TEXT NOT NULL,
                media_type TEXT NOT NULL,
                local_path TEXT NOT NULL DEFAULT '',
                mime_type TEXT NOT NULL DEFAULT '',
                file_name TEXT NOT NULL DEFAULT '',
                file_size INTEGER NOT NULL DEFAULT 0,
                duration_sec INTEGER NOT NULL DEFAULT 0,
                is_voice_note INTEGER NOT NULL DEFAULT 0,
                direct_path TEXT NOT NULL DEFAULT '',
                media_key_b64 TEXT NOT NULL DEFAULT '',
                file_sha256_b64 TEXT NOT NULL DEFAULT '',
                file_enc_sha256_b64 TEXT NOT NULL DEFAULT '',
                download_state TEXT NOT NULL DEFAULT 'none',
                error_message TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (account_id, chat_jid, message_id),
                FOREIGN KEY (account_id, chat_jid, message_id)
                    REFERENCES messages(account_id, chat_jid, message_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS groups (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                group_jid TEXT NOT NULL,
                name TEXT NOT NULL,
                topic TEXT NOT NULL DEFAULT '',
                owner_jid TEXT NOT NULL DEFAULT '',
                participant_count INTEGER NOT NULL DEFAULT 0,
                is_announce INTEGER NOT NULL DEFAULT 0,
                is_locked INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY (account_id, group_jid),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS group_members (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                group_jid TEXT NOT NULL,
                member_jid TEXT NOT NULL,
                phone_jid TEXT NOT NULL DEFAULT '',
                is_admin INTEGER NOT NULL DEFAULT 0,
                is_super_admin INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (account_id, group_jid, member_jid),
                FOREIGN KEY (account_id, group_jid) REFERENCES groups(account_id, group_jid) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS synchronization_state (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                sync_type TEXT NOT NULL,
                last_chunk_index INTEGER NOT NULL DEFAULT 0,
                progress_pct INTEGER NOT NULL DEFAULT 0,
                imported_chats INTEGER NOT NULL DEFAULT 0,
                imported_messages INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'idle',
                updated_at_ms INTEGER NOT NULL,
                PRIMARY KEY (account_id, sync_type),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                setting_key TEXT PRIMARY KEY,
                setting_value TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun seedDefaultAccounts(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        for (acct in 1..4) {
            val cv = ContentValues().apply {
                put("account_id", acct)
                put("label", AccountPalette.defaultLabelFor(acct))
                put("color_hex", AccountPalette.colorHexFor(acct))
                put("jid", "")
                put("phone", "")
                put("push_name", "")
                put("state", AccountStatus.NOT_CONNECTED.wireValue)
                put("error_message", "")
                put("reconnect_attempt", 0)
                put("has_saved_session", 0)
                put("updated_at_ms", now)
            }
            db.insert("accounts", SQLiteDatabase.CONFLICT_IGNORE, cv)
        }
    }

    // --- Accounts & Sessions ---

    fun getAccounts(): List<AccountRecord> {
        val out = mutableListOf<AccountRecord>()
        db.query("SELECT account_id, label, color_hex, jid, phone, push_name, state, error_message, reconnect_attempt, has_saved_session, updated_at_ms FROM accounts ORDER BY account_id ASC")
            .use { c ->
                while (c.moveToNext()) {
                    out.add(
                        AccountRecord(
                            accountId = c.getInt(0),
                            label = c.getString(1),
                            colorHex = c.getLong(2),
                            jid = c.getString(3),
                            phone = c.getString(4),
                            pushName = c.getString(5),
                            status = AccountStatus.fromWire(c.getString(6)),
                            errorMessage = c.getString(7),
                            reconnectAttempt = c.getInt(8),
                            hasSavedSession = c.getInt(9) != 0,
                            updatedAtMs = c.getLong(10)
                        )
                    )
                }
            }
        return out
    }

    fun getAccount(accountId: Int): AccountRecord? {
        requireValidAccount(accountId)
        return getAccounts().firstOrNull { it.accountId == accountId }
    }

    fun updateAccountLabel(accountId: Int, newLabel: String) {
        requireValidAccount(accountId)
        val clean = newLabel.trim().ifEmpty { AccountPalette.defaultLabelFor(accountId) }
        db.execSQL(
            "UPDATE accounts SET label = ?, updated_at_ms = ? WHERE account_id = ?",
            arrayOf<Any>(clean, System.currentTimeMillis(), accountId)
        )
        notifyChanged()
    }

    fun updateAccountState(
        accountId: Int,
        status: AccountStatus,
        jid: String? = null,
        phone: String? = null,
        pushName: String? = null,
        errorMessage: String = "",
        reconnectAttempt: Int = 0,
        hasSavedSession: Boolean? = null
    ) {
        requireValidAccount(accountId)
        val existing = getAccount(accountId) ?: return
        val nextJid = if (!jid.isNullOrBlank()) jid else existing.jid
        val nextPhone = if (!phone.isNullOrBlank()) phone else existing.phone
        val nextPush = if (!pushName.isNullOrBlank()) pushName else existing.pushName
        val nextSaved = hasSavedSession ?: (existing.hasSavedSession || status == AccountStatus.CONNECTED || nextJid.isNotBlank())

        db.execSQL(
            """
            UPDATE accounts
               SET jid = ?, phone = ?, push_name = ?, state = ?, error_message = ?,
                   reconnect_attempt = ?, has_saved_session = ?, updated_at_ms = ?
             WHERE account_id = ?
            """.trimIndent(),
            arrayOf<Any>(
                nextJid,
                nextPhone,
                nextPush,
                status.wireValue,
                errorMessage,
                reconnectAttempt,
                if (nextSaved) 1 else 0,
                System.currentTimeMillis(),
                accountId
            )
        )
        notifyChanged()
    }

    fun upsertSessionMetadata(record: SessionMetadataRecord) {
        requireValidAccount(record.accountId)
        val cv = ContentValues().apply {
            put("account_id", record.accountId)
            put("device_jid", record.deviceJid)
            put("key_alias", record.keyAlias)
            put("paired_at_ms", record.pairedAtMs)
            put("last_connected_at_ms", record.lastConnectedAtMs)
            put("session_dir", record.sessionDir)
        }
        db.insert("sessions", SQLiteDatabase.CONFLICT_REPLACE, cv)
        notifyChanged()
    }

    /**
     * Completely clears ONE account's contacts, chats, messages, media rows, groups, sync state,
     * and resets its slot row to NotConnected without touching the other three accounts.
     */
    fun wipeSingleAccountData(accountId: Int) {
        requireValidAccount(accountId)
        db.beginTransaction()
        try {
            val args = arrayOf<Any>(accountId)
            db.execSQL("DELETE FROM message_media WHERE account_id = ?", args)
            db.execSQL("DELETE FROM messages WHERE account_id = ?", args)
            db.execSQL("DELETE FROM chats WHERE account_id = ?", args)
            db.execSQL("DELETE FROM group_members WHERE account_id = ?", args)
            db.execSQL("DELETE FROM groups WHERE account_id = ?", args)
            db.execSQL("DELETE FROM contacts WHERE account_id = ?", args)
            db.execSQL("DELETE FROM synchronization_state WHERE account_id = ?", args)
            db.execSQL("DELETE FROM sessions WHERE account_id = ?", args)
            db.execSQL(
                """
                UPDATE accounts
                   SET jid = '', phone = '', push_name = '', state = 'not_connected',
                       error_message = '', reconnect_attempt = 0, has_saved_session = 0,
                       updated_at_ms = ?
                 WHERE account_id = ?
                """.trimIndent(),
                arrayOf<Any>(System.currentTimeMillis(), accountId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    // --- Contacts ---

    fun upsertContacts(accountId: Int, items: List<ContactRecord>) {
        requireValidAccount(accountId)
        if (items.isEmpty()) return
        db.beginTransaction()
        try {
            for (item in items) {
                if (item.contactJid.isBlank()) continue
                db.execSQL(
                    """
                    INSERT INTO contacts (account_id, contact_jid, full_name, first_name, push_name, business_name, is_online, last_seen_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, contact_jid) DO UPDATE SET
                        full_name = CASE WHEN excluded.full_name != '' THEN excluded.full_name ELSE contacts.full_name END,
                        first_name = CASE WHEN excluded.first_name != '' THEN excluded.first_name ELSE contacts.first_name END,
                        push_name = CASE WHEN excluded.push_name != '' THEN excluded.push_name ELSE contacts.push_name END,
                        business_name = CASE WHEN excluded.business_name != '' THEN excluded.business_name ELSE contacts.business_name END
                    """.trimIndent(),
                    arrayOf<Any>(
                        accountId,
                        item.contactJid,
                        item.fullName,
                        item.firstName,
                        item.pushName,
                        item.businessName,
                        if (item.isOnline) 1 else 0,
                        item.lastSeenMs
                    )
                )
                val resolvedTitle = item.displayName
                if (resolvedTitle.isNotBlank() && !item.contactJid.endsWith("@g.us")) {
                    db.execSQL(
                        """
                        UPDATE chats SET title = ?
                         WHERE account_id = ? AND chat_jid = ?
                           AND (title = '' OR title = SUBSTR(chat_jid, 1, INSTR(chat_jid, '@') - 1) OR title = chat_jid)
                        """.trimIndent(),
                        arrayOf<Any>(resolvedTitle, accountId, item.contactJid)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    fun updatePresence(accountId: Int, contactJid: String, isOnline: Boolean, lastSeenMs: Long) {
        requireValidAccount(accountId)
        db.execSQL(
            """
            INSERT INTO contacts (account_id, contact_jid, is_online, last_seen_ms)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(account_id, contact_jid) DO UPDATE SET
                is_online = excluded.is_online,
                last_seen_ms = CASE WHEN excluded.last_seen_ms > 0 THEN excluded.last_seen_ms ELSE contacts.last_seen_ms END
            """.trimIndent(),
            arrayOf<Any>(accountId, contactJid, if (isOnline) 1 else 0, lastSeenMs)
        )
        notifyChanged()
    }

    fun getContacts(accountFilter: Int? = null, searchQuery: String = ""): List<ContactRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (accountFilter != null) {
            requireValidAccount(accountFilter)
            clauses.add("account_id = ?")
            args.add(accountFilter)
        }
        if (searchQuery.isNotBlank()) {
            val pat = "%${searchQuery.trim()}%"
            clauses.add("(full_name LIKE ? OR push_name LIKE ? OR business_name LIKE ? OR contact_jid LIKE ?)")
            args.addAll(listOf(pat, pat, pat, pat))
        }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val sql = """
            SELECT account_id, contact_jid, full_name, first_name, push_name, business_name, is_online, last_seen_ms
              FROM contacts
              $where
             ORDER BY COALESCE(NULLIF(full_name,''), NULLIF(push_name,''), contact_jid) COLLATE NOCASE ASC
        """.trimIndent()

        val out = mutableListOf<ContactRecord>()
        db.query(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ContactRecord(
                        accountId = c.getInt(0),
                        contactJid = c.getString(1),
                        fullName = c.getString(2),
                        firstName = c.getString(3),
                        pushName = c.getString(4),
                        businessName = c.getString(5),
                        isOnline = c.getInt(6) != 0,
                        lastSeenMs = c.getLong(7)
                    )
                )
            }
        }
        return out
    }

    // --- Chats & Messages ---

    fun ensureChatExists(accountId: Int, chatJid: String, preferredTitle: String = "", isGroup: Boolean = chatJid.endsWith("@g.us")) {
        requireValidAccount(accountId)
        val composite = "$accountId:$chatJid"
        val fallbackTitle = preferredTitle.ifBlank { resolveDisplayTitleInternal(accountId, chatJid, isGroup) }
        db.execSQL(
            """
            INSERT INTO chats (account_id, chat_jid, composite_key, title, is_group, last_message_ts_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, chat_jid) DO UPDATE SET
                title = CASE WHEN ? != '' THEN ? ELSE chats.title END
            """.trimIndent(),
            arrayOf<Any>(
                accountId,
                chatJid,
                composite,
                fallbackTitle,
                if (isGroup) 1 else 0,
                System.currentTimeMillis(),
                preferredTitle,
                preferredTitle
            )
        )
        notifyChanged()
    }

    private fun resolveDisplayTitleInternal(accountId: Int, chatJid: String, isGroup: Boolean): String {
        if (isGroup) {
            db.query(
                "SELECT name FROM groups WHERE account_id = ? AND group_jid = ?",
                arrayOf<Any>(accountId, chatJid)
            ).use { c ->
                if (c.moveToFirst() && c.getString(0).isNotBlank()) return c.getString(0)
            }
        } else {
            db.query(
                "SELECT full_name, push_name, business_name FROM contacts WHERE account_id = ? AND contact_jid = ?",
                arrayOf<Any>(accountId, chatJid)
            ).use { c ->
                if (c.moveToFirst()) {
                    val candidate = listOf(c.getString(0), c.getString(1), c.getString(2))
                        .firstOrNull { it.isNotBlank() }
                    if (candidate != null) return candidate
                }
            }
        }
        return chatJid.substringBefore('@')
    }

    /**
     * Inserts or updates a message strictly scoped to (account_id, chat_jid, message_id)
     * and atomically updates the owning conversation preview & unread count.
     * Returns true if this was a newly inserted message, false if deduplicated/updated.
     */
    fun upsertMessageAndUpdateChat(
        record: MessageRecord,
        chatTitleHint: String = "",
        incrementUnread: Boolean = false,
        explicitUnreadCount: Int? = null
    ): Boolean {
        requireValidAccount(record.accountId)
        require(record.chatJid.isNotBlank() && record.messageId.isNotBlank()) {
            "chatJid and messageId are mandatory"
        }

        var insertedNew = false
        db.beginTransaction()
        try {
            val chatComp = "${record.accountId}:${record.chatJid}"
            val resolvedTitle = chatTitleHint.ifBlank {
                if (!record.isGroup && !record.isFromMe && record.senderName.isNotBlank()) {
                    record.senderName
                } else {
                    resolveDisplayTitleInternal(record.accountId, record.chatJid, record.isGroup)
                }
            }

            db.execSQL(
                """
                INSERT INTO chats (account_id, chat_jid, composite_key, title, is_group, last_message_ts_ms)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id, chat_jid) DO NOTHING
                """.trimIndent(),
                arrayOf<Any>(
                    record.accountId,
                    record.chatJid,
                    chatComp,
                    resolvedTitle,
                    if (record.isGroup) 1 else 0,
                    record.timestampMs
                )
            )

            val existed = db.query(
                "SELECT 1 FROM messages WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
                arrayOf<Any>(record.accountId, record.chatJid, record.messageId)
            ).use { it.moveToFirst() }

            insertedNew = !existed

            val msgComp = "${record.accountId}:${record.chatJid}:${record.messageId}"
            db.execSQL(
                """
                INSERT INTO messages (
                    account_id, chat_jid, message_id, composite_key, sender_jid, sender_name,
                    is_from_me, is_group, text_body, media_type, quoted_message_id, quoted_sender_jid,
                    quoted_text, reactions_summary, is_deleted, is_edited, status, timestamp_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id, chat_jid, message_id) DO UPDATE SET
                    text_body = CASE WHEN excluded.text_body != '' THEN excluded.text_body ELSE messages.text_body END,
                    is_edited = CASE WHEN excluded.is_edited = 1 THEN 1 ELSE messages.is_edited END,
                    status = excluded.status
                """.trimIndent(),
                arrayOf<Any>(
                    record.accountId,
                    record.chatJid,
                    record.messageId,
                    msgComp,
                    record.senderJid,
                    record.senderName,
                    if (record.isFromMe) 1 else 0,
                    if (record.isGroup) 1 else 0,
                    record.textBody,
                    record.mediaType,
                    record.quotedMessageId,
                    record.quotedSenderJid,
                    record.quotedText,
                    record.reactionsSummary,
                    if (record.isDeleted) 1 else 0,
                    if (record.isEdited) 1 else 0,
                    record.status,
                    record.timestampMs
                )
            )

            record.media?.let { m ->
                db.execSQL(
                    """
                    INSERT INTO message_media (
                        account_id, chat_jid, message_id, media_type, local_path, mime_type, file_name,
                        file_size, duration_sec, is_voice_note, direct_path, media_key_b64,
                        file_sha256_b64, file_enc_sha256_b64, download_state, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, chat_jid, message_id) DO UPDATE SET
                        local_path = CASE WHEN excluded.local_path != '' THEN excluded.local_path ELSE message_media.local_path END,
                        download_state = CASE WHEN excluded.local_path != '' THEN 'ready' ELSE message_media.download_state END
                    """.trimIndent(),
                    arrayOf<Any>(
                        record.accountId,
                        record.chatJid,
                        record.messageId,
                        m.mediaType,
                        m.localPath,
                        m.mimeType,
                        m.fileName,
                        m.fileSize,
                        m.durationSec,
                        if (m.isVoiceNote) 1 else 0,
                        m.directPath,
                        m.mediaKeyB64,
                        m.fileSha256B64,
                        m.fileEncSha256B64,
                        m.downloadState,
                        m.errorMessage
                    )
                )
            }

            val preview = buildPreviewText(record)
            val unreadDelta = if (insertedNew && incrementUnread && !record.isFromMe) 1 else 0

            if (explicitUnreadCount != null && explicitUnreadCount > 0) {
                db.execSQL(
                    """
                    UPDATE chats
                       SET title = CASE WHEN ? != '' AND (title = '' OR title LIKE '%@%' OR title = SUBSTR(chat_jid, 1, INSTR(chat_jid, '@') - 1)) THEN ? ELSE title END,
                           last_message_id = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_id END,
                           last_message_preview = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_preview END,
                           last_sender_name = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_sender_name END,
                           last_message_ts_ms = MAX(last_message_ts_ms, ?),
                           unread_count = MAX(unread_count, ?)
                     WHERE account_id = ? AND chat_jid = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        resolvedTitle, resolvedTitle,
                        record.timestampMs, record.messageId,
                        record.timestampMs, preview,
                        record.timestampMs, record.senderName,
                        record.timestampMs,
                        explicitUnreadCount,
                        record.accountId, record.chatJid
                    )
                )
            } else {
                db.execSQL(
                    """
                    UPDATE chats
                       SET title = CASE WHEN ? != '' AND (title = '' OR title LIKE '%@%' OR title = SUBSTR(chat_jid, 1, INSTR(chat_jid, '@') - 1)) THEN ? ELSE title END,
                           last_message_id = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_id END,
                           last_message_preview = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_preview END,
                           last_sender_name = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_sender_name END,
                           last_message_ts_ms = MAX(last_message_ts_ms, ?),
                           unread_count = unread_count + ?
                     WHERE account_id = ? AND chat_jid = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        resolvedTitle, resolvedTitle,
                        record.timestampMs, record.messageId,
                        record.timestampMs, preview,
                        record.timestampMs, record.senderName,
                        record.timestampMs,
                        unreadDelta,
                        record.accountId, record.chatJid
                    )
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
        return insertedNew
    }

    private fun buildPreviewText(msg: MessageRecord): String {
        if (msg.isDeleted) return "Deleted message"
        val mediaTag = when (msg.mediaType) {
            "image" -> "[Photo] "
            "video" -> "[Video] "
            "voice" -> "[Voice note] "
            "audio" -> "[Audio] "
            "document" -> "[Document] "
            "sticker" -> "[Sticker]"
            else -> ""
        }
        return (mediaTag + msg.textBody).trim().ifEmpty { mediaTag.trim() }
    }

    fun getUnifiedInboxChats(
        accountFilter: Int? = null,
        unreadOnly: Boolean = false,
        chatTypeFilter: String = "all", // "all", "groups", "direct"
        searchQuery: String = ""
    ): List<ChatSummaryRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (accountFilter != null) {
            requireValidAccount(accountFilter)
            clauses.add("c.account_id = ?")
            args.add(accountFilter)
        }
        if (unreadOnly) {
            clauses.add("c.unread_count > 0")
        }
        when (chatTypeFilter) {
            "groups" -> clauses.add("c.is_group = 1")
            "direct" -> clauses.add("c.is_group = 0")
        }
        if (searchQuery.isNotBlank()) {
            val pat = "%${searchQuery.trim()}%"
            clauses.add(
                """
                (c.title LIKE ? OR c.chat_jid LIKE ? OR c.last_message_preview LIKE ?
                 OR EXISTS (
                     SELECT 1 FROM messages m
                      WHERE m.account_id = c.account_id AND m.chat_jid = c.chat_jid AND m.text_body LIKE ?
                 ))
                """.trimIndent()
            )
            args.addAll(listOf(pat, pat, pat, pat))
        }

        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val sql = """
            SELECT c.account_id, c.chat_jid, c.composite_key, c.title, c.is_group,
                   c.last_message_id, c.last_message_preview, c.last_message_ts_ms,
                   c.last_sender_name, c.unread_count, c.is_typing, c.typing_sender
              FROM chats c
              $where
             ORDER BY c.last_message_ts_ms DESC, c.account_id ASC
        """.trimIndent()

        val out = mutableListOf<ChatSummaryRecord>()
        db.query(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatSummaryRecord(
                        accountId = c.getInt(0),
                        chatJid = c.getString(1),
                        compositeKey = c.getString(2),
                        title = c.getString(3),
                        isGroup = c.getInt(4) != 0,
                        lastMessageId = c.getString(5),
                        lastMessagePreview = c.getString(6),
                        lastMessageTsMs = c.getLong(7),
                        lastSenderName = c.getString(8),
                        unreadCount = c.getInt(9),
                        isTyping = c.getInt(10) != 0,
                        typingSender = c.getString(11)
                    )
                )
            }
        }
        return out
    }

    fun getChatSummary(accountId: Int, chatJid: String): ChatSummaryRecord? {
        requireValidAccount(accountId)
        return getUnifiedInboxChats(accountFilter = accountId).firstOrNull { it.chatJid == chatJid }
    }

    fun getConversationMessages(accountId: Int, chatJid: String, limit: Int = 400): List<MessageRecord> {
        requireValidAccount(accountId)
        val sql = """
            SELECT m.account_id, m.chat_jid, m.message_id, m.composite_key, m.sender_jid, m.sender_name,
                   m.is_from_me, m.is_group, m.text_body, m.media_type, m.quoted_message_id,
                   m.quoted_sender_jid, m.quoted_text, m.reactions_summary, m.is_deleted, m.is_edited,
                   m.status, m.timestamp_ms,
                   mm.local_path, mm.mime_type, mm.file_name, mm.file_size, mm.duration_sec,
                   mm.is_voice_note, mm.direct_path, mm.media_key_b64, mm.file_sha256_b64,
                   mm.file_enc_sha256_b64, mm.download_state, mm.error_message
              FROM messages m
              LEFT JOIN message_media mm
                ON mm.account_id = m.account_id AND mm.chat_jid = m.chat_jid AND mm.message_id = m.message_id
             WHERE m.account_id = ? AND m.chat_jid = ?
             ORDER BY m.timestamp_ms ASC
             LIMIT ?
        """.trimIndent()

        val out = mutableListOf<MessageRecord>()
        db.query(sql, arrayOf<Any>(accountId, chatJid, limit)).use { c ->
            while (c.moveToNext()) {
                val mediaType = c.getString(9)
                val media = if (mediaType.isNotBlank()) {
                    MessageMediaRecord(
                        accountId = c.getInt(0),
                        chatJid = c.getString(1),
                        messageId = c.getString(2),
                        mediaType = mediaType,
                        localPath = c.getString(18) ?: "",
                        mimeType = c.getString(19) ?: "",
                        fileName = c.getString(20) ?: "",
                        fileSize = c.getLong(21),
                        durationSec = c.getInt(22),
                        isVoiceNote = c.getInt(23) != 0,
                        directPath = c.getString(24) ?: "",
                        mediaKeyB64 = c.getString(25) ?: "",
                        fileSha256B64 = c.getString(26) ?: "",
                        fileEncSha256B64 = c.getString(27) ?: "",
                        downloadState = c.getString(28) ?: "none",
                        errorMessage = c.getString(29) ?: ""
                    )
                } else {
                    null
                }
                out.add(
                    MessageRecord(
                        accountId = c.getInt(0),
                        chatJid = c.getString(1),
                        messageId = c.getString(2),
                        compositeKey = c.getString(3),
                        senderJid = c.getString(4),
                        senderName = c.getString(5),
                        isFromMe = c.getInt(6) != 0,
                        isGroup = c.getInt(7) != 0,
                        textBody = c.getString(8),
                        mediaType = mediaType,
                        quotedMessageId = c.getString(10),
                        quotedSenderJid = c.getString(11),
                        quotedText = c.getString(12),
                        reactionsSummary = c.getString(13),
                        isDeleted = c.getInt(14) != 0,
                        isEdited = c.getInt(15) != 0,
                        status = c.getString(16),
                        timestampMs = c.getLong(17),
                        media = media
                    )
                )
            }
        }
        return out
    }

    fun clearChatUnread(accountId: Int, chatJid: String): List<Pair<String, String>> {
        requireValidAccount(accountId)
        val unreadIdsAndSenders = mutableListOf<Pair<String, String>>()
        db.query(
            """
            SELECT message_id, sender_jid FROM messages
             WHERE account_id = ? AND chat_jid = ? AND is_from_me = 0 AND status != 'read'
             ORDER BY timestamp_ms DESC LIMIT 25
            """.trimIndent(),
            arrayOf<Any>(accountId, chatJid)
        ).use { c ->
            while (c.moveToNext()) {
                unreadIdsAndSenders.add(c.getString(0) to c.getString(1))
            }
        }
        db.execSQL(
            "UPDATE chats SET unread_count = 0 WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(accountId, chatJid)
        )
        db.execSQL(
            "UPDATE messages SET status = 'read' WHERE account_id = ? AND chat_jid = ? AND is_from_me = 0",
            arrayOf<Any>(accountId, chatJid)
        )
        notifyChanged()
        return unreadIdsAndSenders
    }

    fun updateMessageReceiptStatus(accountId: Int, chatJid: String, messageIds: List<String>, newStatus: String) {
        requireValidAccount(accountId)
        if (messageIds.isEmpty()) return
        db.beginTransaction()
        try {
            for (msgId in messageIds) {
                db.execSQL(
                    """
                    UPDATE messages
                       SET status = CASE
                           WHEN status = 'read' THEN 'read'
                           ELSE ?
                       END
                     WHERE account_id = ? AND chat_jid = ? AND message_id = ?
                    """.trimIndent(),
                    arrayOf<Any>(newStatus, accountId, chatJid, msgId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    fun applyReaction(accountId: Int, chatJid: String, targetMessageId: String, emoji: String) {
        requireValidAccount(accountId)
        db.execSQL(
            "UPDATE messages SET reactions_summary = ? WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
            arrayOf<Any>(emoji, accountId, chatJid, targetMessageId)
        )
        notifyChanged()
    }

    fun markMessageRevoked(accountId: Int, chatJid: String, targetMessageId: String) {
        requireValidAccount(accountId)
        db.beginTransaction()
        try {
            db.execSQL(
                """
                UPDATE messages
                   SET is_deleted = 1, text_body = '', reactions_summary = ''
                 WHERE account_id = ? AND chat_jid = ? AND message_id = ?
                """.trimIndent(),
                arrayOf<Any>(accountId, chatJid, targetMessageId)
            )
            db.execSQL(
                """
                UPDATE chats
                   SET last_message_preview = 'Deleted message'
                 WHERE account_id = ? AND chat_jid = ? AND last_message_id = ?
                """.trimIndent(),
                arrayOf<Any>(accountId, chatJid, targetMessageId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    fun updateMediaDownloadResult(
        accountId: Int,
        chatJid: String,
        messageId: String,
        localPath: String,
        downloadState: String,
        errorMessage: String = ""
    ) {
        requireValidAccount(accountId)
        db.execSQL(
            """
            UPDATE message_media
               SET local_path = CASE WHEN ? != '' THEN ? ELSE local_path END,
                   download_state = ?,
                   error_message = ?
             WHERE account_id = ? AND chat_jid = ? AND message_id = ?
            """.trimIndent(),
            arrayOf<Any>(localPath, localPath, downloadState, errorMessage, accountId, chatJid, messageId)
        )
        notifyChanged()
    }

    fun updateChatTyping(accountId: Int, chatJid: String, isTyping: Boolean, senderJid: String) {
        requireValidAccount(accountId)
        db.execSQL(
            "UPDATE chats SET is_typing = ?, typing_sender = ? WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(if (isTyping) 1 else 0, senderJid, accountId, chatJid)
        )
        notifyChanged()
    }

    // --- Groups & Group Members ---

    fun upsertGroups(accountId: Int, groups: List<GroupRecord>) {
        requireValidAccount(accountId)
        if (groups.isEmpty()) return
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (g in groups) {
                if (g.groupJid.isBlank()) continue
                db.execSQL(
                    """
                    INSERT INTO groups (
                        account_id, group_jid, name, topic, owner_jid, participant_count,
                        is_announce, is_locked, created_at_ms, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, group_jid) DO UPDATE SET
                        name = excluded.name,
                        topic = excluded.topic,
                        owner_jid = excluded.owner_jid,
                        participant_count = excluded.participant_count,
                        is_announce = excluded.is_announce,
                        is_locked = excluded.is_locked,
                        updated_at_ms = excluded.updated_at_ms
                    """.trimIndent(),
                    arrayOf<Any>(
                        accountId,
                        g.groupJid,
                        g.name,
                        g.topic,
                        g.ownerJid,
                        g.participantCount,
                        if (g.isAnnounce) 1 else 0,
                        if (g.isLocked) 1 else 0,
                        g.createdAtMs,
                        now
                    )
                )
                if (g.members.isNotEmpty()) {
                    db.execSQL(
                        "DELETE FROM group_members WHERE account_id = ? AND group_jid = ?",
                        arrayOf<Any>(accountId, g.groupJid)
                    )
                    for (m in g.members) {
                        db.execSQL(
                            """
                            INSERT INTO group_members (account_id, group_jid, member_jid, phone_jid, is_admin, is_super_admin)
                            VALUES (?, ?, ?, ?, ?, ?)
                            ON CONFLICT(account_id, group_jid, member_jid) DO NOTHING
                            """.trimIndent(),
                            arrayOf<Any>(
                                accountId,
                                g.groupJid,
                                m.memberJid,
                                m.phoneJid,
                                if (m.isAdmin) 1 else 0,
                                if (m.isSuperAdmin) 1 else 0
                            )
                        )
                    }
                }
                if (g.name.isNotBlank()) {
                    db.execSQL(
                        "UPDATE chats SET title = ?, is_group = 1 WHERE account_id = ? AND chat_jid = ?",
                        arrayOf<Any>(g.name, accountId, g.groupJid)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        notifyChanged()
    }

    fun getGroups(accountFilter: Int? = null, searchQuery: String = ""): List<GroupRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (accountFilter != null) {
            requireValidAccount(accountFilter)
            clauses.add("account_id = ?")
            args.add(accountFilter)
        }
        if (searchQuery.isNotBlank()) {
            val pat = "%${searchQuery.trim()}%"
            clauses.add("(name LIKE ? OR topic LIKE ? OR group_jid LIKE ?)")
            args.addAll(listOf(pat, pat, pat))
        }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val sql = """
            SELECT account_id, group_jid, name, topic, owner_jid, participant_count,
                   is_announce, is_locked, created_at_ms, updated_at_ms
              FROM groups
              $where
             ORDER BY name COLLATE NOCASE ASC
        """.trimIndent()

        val out = mutableListOf<GroupRecord>()
        db.query(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val acct = c.getInt(0)
                val gJid = c.getString(1)
                out.add(
                    GroupRecord(
                        accountId = acct,
                        groupJid = gJid,
                        name = c.getString(2),
                        topic = c.getString(3),
                        ownerJid = c.getString(4),
                        participantCount = c.getInt(5),
                        isAnnounce = c.getInt(6) != 0,
                        isLocked = c.getInt(7) != 0,
                        createdAtMs = c.getLong(8),
                        updatedAtMs = c.getLong(9)
                    )
                )
            }
        }
        return out
    }

    fun getGroupMembers(accountId: Int, groupJid: String): List<GroupMemberRecord> {
        requireValidAccount(accountId)
        val out = mutableListOf<GroupMemberRecord>()
        db.query(
            """
            SELECT account_id, group_jid, member_jid, phone_jid, is_admin, is_super_admin
              FROM group_members
             WHERE account_id = ? AND group_jid = ?
             ORDER BY is_super_admin DESC, is_admin DESC, member_jid ASC
            """.trimIndent(),
            arrayOf<Any>(accountId, groupJid)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    GroupMemberRecord(
                        accountId = c.getInt(0),
                        groupJid = c.getString(1),
                        memberJid = c.getString(2),
                        phoneJid = c.getString(3),
                        isAdmin = c.getInt(4) != 0,
                        isSuperAdmin = c.getInt(5) != 0
                    )
                )
            }
        }
        return out
    }

    // --- Synchronization State ---

    fun upsertSyncState(record: SyncStateRecord) {
        requireValidAccount(record.accountId)
        db.execSQL(
            """
            INSERT INTO synchronization_state (
                account_id, sync_type, last_chunk_index, progress_pct,
                imported_chats, imported_messages, status, updated_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, sync_type) DO UPDATE SET
                last_chunk_index = MAX(synchronization_state.last_chunk_index, excluded.last_chunk_index),
                progress_pct = MAX(synchronization_state.progress_pct, excluded.progress_pct),
                imported_chats = excluded.imported_chats,
                imported_messages = excluded.imported_messages,
                status = excluded.status,
                updated_at_ms = excluded.updated_at_ms
            """.trimIndent(),
            arrayOf<Any>(
                record.accountId,
                record.syncType,
                record.lastChunkIndex,
                record.progressPct,
                record.importedChats,
                record.importedMessages,
                record.status,
                record.updatedAtMs
            )
        )
        notifyChanged()
    }

    fun getSyncStates(accountId: Int): List<SyncStateRecord> {
        requireValidAccount(accountId)
        val out = mutableListOf<SyncStateRecord>()
        db.query(
            """
            SELECT account_id, sync_type, last_chunk_index, progress_pct,
                   imported_chats, imported_messages, status, updated_at_ms
              FROM synchronization_state
             WHERE account_id = ?
             ORDER BY updated_at_ms DESC
            """.trimIndent(),
            arrayOf<Any>(accountId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SyncStateRecord(
                        accountId = c.getInt(0),
                        syncType = c.getString(1),
                        lastChunkIndex = c.getInt(2),
                        progressPct = c.getInt(3),
                        importedChats = c.getInt(4),
                        importedMessages = c.getInt(5),
                        status = c.getString(6),
                        updatedAtMs = c.getLong(7)
                    )
                )
            }
        }
        return out
    }

    // --- App Settings ---

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("setting_key", key)
            put("setting_value", value)
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.insert("app_settings", SQLiteDatabase.CONFLICT_REPLACE, cv)
        notifyChanged()
    }

    fun getSetting(key: String, defaultValue: String = ""): String {
        db.query(
            SupportSQLiteQueryBuilder.builder("app_settings")
                .columns(arrayOf("setting_value"))
                .selection("setting_key = ?", arrayOf(key))
                .create()
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else defaultValue
        }
    }

    fun clearLocalMediaReferences() {
        db.execSQL("UPDATE message_media SET local_path = '', download_state = 'none'")
        notifyChanged()
    }

    fun checkpointForBackup() {
        try {
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        } catch (_: Throwable) {
        }
    }

    fun close() {
        openHelper.close()
    }

    companion object {
        const val DB_FILE_NAME = "whatshub_encrypted.db"
        private const val DB_VERSION = 1

        fun createEncrypted(context: Context, keyManager: KeystoreKeyManager): WhatsAppHubDatabase {
            System.loadLibrary("sqlcipher")
            Logger.setTarget(NoopTarget())
            val keyBytes = keyManager.getOrCreateDatabaseKey()
            val factory = SupportOpenHelperFactory(keyBytes)
            return WhatsAppHubDatabase(context.applicationContext, DB_FILE_NAME, factory)
        }
    }
}
