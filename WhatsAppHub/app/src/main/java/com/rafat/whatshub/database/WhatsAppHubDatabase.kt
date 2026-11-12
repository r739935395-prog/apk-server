package com.rafat.whatshub.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import com.rafat.whatshub.security.KeystoreKeyManager
import com.rafat.whatshub.security.SanitizedLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Encrypted SQLCipher database manager for Rafat Hub v1.1.0 (DB_VERSION = 2).
 *
 * Domain tables:
 *   1. accounts
 *   2. sessions
 *   3. identity_mappings  (v1.1.0 per-account verified @lid <-> @s.whatsapp.net resolution)
 *   4. inbound_event_journal (v1.1.0 durable event deduplication & crash recovery)
 *   5. contacts
 *   6. chats
 *   7. messages
 *   8. message_media
 *   9. groups
 *  10. group_members
 *  11. synchronization_state
 *  12. app_settings
 *
 * Strictly enforces composite primary keys per account (1..4) and domain-scoped
 * invalidation flows so 120 Hz UI screens never suffer global recomposition storms.
 */
class WhatsAppHubDatabase(
    context: Context,
    dbName: String? = DB_FILE_NAME,
    factory: SupportSQLiteOpenHelper.Factory
) {
    private val openHelper: SupportSQLiteOpenHelper

    // Legacy aggregate counter retained for test compatibility
    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    // Fine-grained domain invalidation streams (eliminates global UI recomposition storms)
    private val _accountsInvalidations = MutableStateFlow(0L)
    val accountsInvalidations: StateFlow<Long> = _accountsInvalidations.asStateFlow()

    private val _inboxInvalidations = MutableStateFlow(0L)
    val inboxInvalidations: StateFlow<Long> = _inboxInvalidations.asStateFlow()

    private val _contactsGroupsInvalidations = MutableStateFlow(0L)
    val contactsGroupsInvalidations: StateFlow<Long> = _contactsGroupsInvalidations.asStateFlow()

    private val _settingsInvalidations = MutableStateFlow(0L)
    val settingsInvalidations: StateFlow<Long> = _settingsInvalidations.asStateFlow()

    private val _conversationInvalidations = MutableSharedFlow<Pair<Int, String>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val conversationInvalidations: SharedFlow<Pair<Int, String>> = _conversationInvalidations.asSharedFlow()

    // Emits (accountId, oldLidJid, newCanonicalPnJid) when an active @lid thread merges into @s.whatsapp.net
    private val _jidMergeRedirects = MutableSharedFlow<Triple<Int, String, String>>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val jidMergeRedirects: SharedFlow<Triple<Int, String, String>> = _jidMergeRedirects.asSharedFlow()

    init {
        val callback = object : SupportSQLiteOpenHelper.Callback(DB_VERSION) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                createSchemaV2(db)
                seedDefaultAccounts(db)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                SanitizedLogger.info("DB", "Running non-destructive SQLite migration v$oldVersion -> v$newVersion")
                if (oldVersion < 2) {
                    migrateV1ToV2(db)
                }
                seedDefaultAccounts(db)
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Rule 17.7: NEVER silently delete or rename existing user database files.
                SanitizedLogger.error(
                    "DB",
                    "Database integrity error reported by SQLite; preserving database file intact for recovery."
                )
            }
        }

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        openHelper = factory.create(config)
        openHelper.writableDatabase
    }

    private val db: SupportSQLiteDatabase
        get() = openHelper.writableDatabase

    private fun bumpAccounts() {
        _accountsInvalidations.value += 1L
        _changeVersion.value += 1L
    }

    private fun bumpInbox() {
        _inboxInvalidations.value += 1L
        _changeVersion.value += 1L
    }

    private fun bumpConversation(accountId: Int, chatJid: String, alsoInbox: Boolean = true) {
        _conversationInvalidations.tryEmit(accountId to chatJid)
        if (alsoInbox) {
            _inboxInvalidations.value += 1L
        }
        _changeVersion.value += 1L
    }

    private fun bumpContactsGroups() {
        _contactsGroupsInvalidations.value += 1L
        _changeVersion.value += 1L
    }

    private fun bumpSettings() {
        _settingsInvalidations.value += 1L
        _changeVersion.value += 1L
    }

    private fun requireValidAccount(accountId: Int) {
        require(accountId in 1..4) { "Invalid account_id $accountId: must be in 1..4" }
    }

    private fun hasColumn(targetDb: SupportSQLiteDatabase, table: String, column: String): Boolean {
        targetDb.query("PRAGMA table_info($table)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0 && c.getString(nameIdx).equals(column, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun addColumnIfMissing(targetDb: SupportSQLiteDatabase, table: String, column: String, definition: String) {
        if (!hasColumn(targetDb, table, column)) {
            targetDb.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }

    /**
     * Non-destructive upgrade from v1.0.0 (DB_VERSION 1) -> v1.1.0 (DB_VERSION 2).
     * Preserves 100% of accounts, sessions, contacts, chats, messages, media, groups, and settings.
     */
    internal fun migrateV1ToV2(targetDb: SupportSQLiteDatabase) {
        targetDb.beginTransaction()
        try {
            createV2AuxiliaryTablesAndIndexesOnly(targetDb)

            // Contacts: verified LID/PN linkage columns
            addColumnIfMissing(targetDb, "contacts", "lid_jid", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(targetDb, "contacts", "pn_jid", "TEXT NOT NULL DEFAULT ''")

            // Chats: pin, archive, mute, mark-unread, and LID/PN tracking
            addColumnIfMissing(targetDb, "chats", "is_pinned", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(targetDb, "chats", "pinned_at_ms", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(targetDb, "chats", "is_archived", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(targetDb, "chats", "is_muted", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(targetDb, "chats", "marked_unread", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(targetDb, "chats", "lid_jid", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(targetDb, "chats", "pn_jid", "TEXT NOT NULL DEFAULT ''")

            // Messages: raw protocol JID, deterministic client ID, server ACK flag, failure reason
            addColumnIfMissing(targetDb, "messages", "raw_chat_jid", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(targetDb, "messages", "client_msg_id", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(targetDb, "messages", "transmission_ack", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(targetDb, "messages", "error_reason", "TEXT NOT NULL DEFAULT ''")

            createV2Indexes(targetDb)
            targetDb.setTransactionSuccessful()
        } finally {
            targetDb.endTransaction()
        }
    }

    private fun createV2AuxiliaryTablesAndIndexesOnly(targetDb: SupportSQLiteDatabase) {
        targetDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS identity_mappings (
                account_id INTEGER NOT NULL CHECK (account_id BETWEEN 1 AND 4),
                lid_jid TEXT NOT NULL,
                pn_jid TEXT NOT NULL,
                mapping_source TEXT NOT NULL DEFAULT 'verified',
                updated_at_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (account_id, lid_jid),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        targetDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS inbound_event_journal (
                dedup_key TEXT PRIMARY KEY,
                account_id INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                processed_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createV2Indexes(targetDb: SupportSQLiteDatabase) {
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_identity_pn ON identity_mappings(account_id, pn_jid)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_journal_ts ON inbound_event_journal(processed_ms)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_last_ts ON chats(last_message_ts_ms DESC)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_account_ts ON chats(account_id, last_message_ts_ms DESC)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_inbox_v2 ON chats(is_archived ASC, is_pinned DESC, pinned_at_ms DESC, last_message_ts_ms DESC)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_chat_ts ON messages(account_id, chat_jid, timestamp_ms DESC)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_acct_msgid ON messages(account_id, message_id)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_client_id ON messages(account_id, client_msg_id)")
        targetDb.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_status ON messages(status, transmission_ack)")
    }

    private fun createSchemaV2(db: SupportSQLiteDatabase) {
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
                updated_at_ms INTEGER NOT NULL DEFAULT 0
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

        createV2AuxiliaryTablesAndIndexesOnly(db)

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
                lid_jid TEXT NOT NULL DEFAULT '',
                pn_jid TEXT NOT NULL DEFAULT '',
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
                title TEXT NOT NULL DEFAULT '',
                is_group INTEGER NOT NULL DEFAULT 0,
                last_message_id TEXT NOT NULL DEFAULT '',
                last_message_preview TEXT NOT NULL DEFAULT '',
                last_message_ts_ms INTEGER NOT NULL DEFAULT 0,
                last_sender_name TEXT NOT NULL DEFAULT '',
                unread_count INTEGER NOT NULL DEFAULT 0,
                is_typing INTEGER NOT NULL DEFAULT 0,
                typing_sender TEXT NOT NULL DEFAULT '',
                is_pinned INTEGER NOT NULL DEFAULT 0,
                pinned_at_ms INTEGER NOT NULL DEFAULT 0,
                is_archived INTEGER NOT NULL DEFAULT 0,
                is_muted INTEGER NOT NULL DEFAULT 0,
                marked_unread INTEGER NOT NULL DEFAULT 0,
                lid_jid TEXT NOT NULL DEFAULT '',
                pn_jid TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (account_id, chat_jid),
                FOREIGN KEY (account_id) REFERENCES accounts(account_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

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
                status TEXT NOT NULL DEFAULT 'sent',
                timestamp_ms INTEGER NOT NULL DEFAULT 0,
                raw_chat_jid TEXT NOT NULL DEFAULT '',
                client_msg_id TEXT NOT NULL DEFAULT '',
                transmission_ack INTEGER NOT NULL DEFAULT 0,
                error_reason TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (account_id, chat_jid, message_id),
                FOREIGN KEY (account_id, chat_jid) REFERENCES chats(account_id, chat_jid) ON DELETE CASCADE
            )
            """.trimIndent()
        )

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
                name TEXT NOT NULL DEFAULT '',
                topic TEXT NOT NULL DEFAULT '',
                owner_jid TEXT NOT NULL DEFAULT '',
                participant_count INTEGER NOT NULL DEFAULT 0,
                is_announce INTEGER NOT NULL DEFAULT 0,
                is_locked INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL DEFAULT 0,
                updated_at_ms INTEGER NOT NULL DEFAULT 0,
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

        createV2Indexes(db)
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

    // =========================================================================
    // DURABLE INBOUND EVENT DEDUPLICATION JOURNAL
    // =========================================================================

    fun hasProcessedJournalKey(dedupKey: String): Boolean {
        if (dedupKey.isBlank()) return false
        db.query(
            "SELECT 1 FROM inbound_event_journal WHERE dedup_key = ?",
            arrayOf<Any>(dedupKey)
        ).use { return it.moveToFirst() }
    }

    fun recordProcessedJournalKey(dedupKey: String, accountId: Int, eventType: String) {
        if (dedupKey.isBlank()) return
        val now = System.currentTimeMillis()
        db.execSQL(
            """
            INSERT INTO inbound_event_journal (dedup_key, account_id, event_type, processed_ms)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(dedup_key) DO NOTHING
            """.trimIndent(),
            arrayOf<Any>(dedupKey, accountId, eventType, now)
        )
    }

    fun pruneOldJournalEntries(olderThanMs: Long = System.currentTimeMillis() - 7L * 24L * 3600_000L) {
        db.execSQL("DELETE FROM inbound_event_journal WHERE processed_ms < ?", arrayOf<Any>(olderThanMs))
    }

    // =========================================================================
    // PER-ACCOUNT @lid <-> @s.whatsapp.net IDENTITY RESOLUTION & SAFE MERGE
    // =========================================================================

    /**
     * Resolves [rawOrCanonicalJid] to the canonical JID for [accountId].
     * If [rawOrCanonicalJid] is an @lid JID that has a verified @s.whatsapp.net mapping on THIS account,
     * returns the verified @s.whatsapp.net JID; otherwise returns normalized [rawOrCanonicalJid].
     */
    fun resolveCanonicalChatJid(accountId: Int, rawOrCanonicalJid: String): String {
        requireValidAccount(accountId)
        val norm = IdentityResolver.normalizeJid(rawOrCanonicalJid)
        if (!IdentityResolver.isLidJid(norm)) return norm
        db.query(
            "SELECT pn_jid FROM identity_mappings WHERE account_id = ? AND lid_jid = ?",
            arrayOf<Any>(accountId, norm)
        ).use { c ->
            if (c.moveToFirst()) {
                val pn = c.getString(0)
                if (pn.isNotBlank()) return pn
            }
        }
        return norm
    }

    fun getLidForPn(accountId: Int, pnJid: String): String {
        requireValidAccount(accountId)
        val norm = IdentityResolver.normalizeJid(pnJid)
        if (!IdentityResolver.isPhoneJid(norm)) return ""
        db.query(
            "SELECT lid_jid FROM identity_mappings WHERE account_id = ? AND pn_jid = ? LIMIT 1",
            arrayOf<Any>(accountId, norm)
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else ""
        }
    }

    fun getIdentityMappings(accountId: Int? = null): List<IdentityMappingRecord> {
        val out = mutableListOf<IdentityMappingRecord>()
        val sql = if (accountId != null) {
            requireValidAccount(accountId)
            "SELECT account_id, lid_jid, pn_jid, mapping_source, updated_at_ms FROM identity_mappings WHERE account_id = ?"
        } else {
            "SELECT account_id, lid_jid, pn_jid, mapping_source, updated_at_ms FROM identity_mappings"
        }
        val args = if (accountId != null) arrayOf<Any>(accountId) else emptyArray()
        db.query(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    IdentityMappingRecord(
                        accountId = c.getInt(0),
                        lidJid = c.getString(1),
                        pnJid = c.getString(2),
                        mappingSource = c.getString(3),
                        updatedAtMs = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    /**
     * Records a verified (lidJid <-> pnJid) pair strictly for [accountId] and atomically
     * merges any pre-existing split @lid conversation, messages, media, and contact record
     * into the canonical @s.whatsapp.net conversation on THAT SAME account.
     */
    fun recordIdentityMappingAndMerge(
        accountId: Int,
        lidJidRaw: String,
        pnJidRaw: String,
        source: String = "verified"
    ): Boolean {
        requireValidAccount(accountId)
        val lidJid = IdentityResolver.normalizeJid(lidJidRaw)
        val pnJid = IdentityResolver.normalizeJid(pnJidRaw)
        if (!IdentityResolver.isLidJid(lidJid) || !IdentityResolver.isPhoneJid(pnJid)) {
            return false
        }

        val now = System.currentTimeMillis()
        var mergedChats = false

        db.beginTransaction()
        try {
            db.execSQL(
                """
                INSERT INTO identity_mappings (account_id, lid_jid, pn_jid, mapping_source, updated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(account_id, lid_jid) DO UPDATE SET
                    pn_jid = excluded.pn_jid,
                    mapping_source = excluded.mapping_source,
                    updated_at_ms = excluded.updated_at_ms
                """.trimIndent(),
                arrayOf<Any>(accountId, lidJid, pnJid, source, now)
            )

            // Merge contact rows if an @lid contact row existed before the verified PN arrived
            val lidContact = db.query(
                "SELECT full_name, first_name, push_name, business_name, is_online, last_seen_ms FROM contacts WHERE account_id = ? AND contact_jid = ?",
                arrayOf<Any>(accountId, lidJid)
            ).use { c ->
                if (c.moveToFirst()) {
                    ContactRecord(
                        accountId = accountId,
                        contactJid = lidJid,
                        fullName = c.getString(0),
                        firstName = c.getString(1),
                        pushName = c.getString(2),
                        businessName = c.getString(3),
                        isOnline = c.getInt(4) != 0,
                        lastSeenMs = c.getLong(5),
                        lidJid = lidJid,
                        pnJid = pnJid
                    )
                } else null
            }

            if (lidContact != null) {
                db.execSQL(
                    """
                    INSERT INTO contacts (account_id, contact_jid, full_name, first_name, push_name, business_name, is_online, last_seen_ms, lid_jid, pn_jid)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, contact_jid) DO UPDATE SET
                        full_name = CASE WHEN contacts.full_name != '' THEN contacts.full_name ELSE excluded.full_name END,
                        first_name = CASE WHEN contacts.first_name != '' THEN contacts.first_name ELSE excluded.first_name END,
                        push_name = CASE WHEN contacts.push_name != '' THEN contacts.push_name ELSE excluded.push_name END,
                        business_name = CASE WHEN contacts.business_name != '' THEN contacts.business_name ELSE excluded.business_name END,
                        last_seen_ms = MAX(contacts.last_seen_ms, excluded.last_seen_ms),
                        lid_jid = excluded.lid_jid,
                        pn_jid = excluded.pn_jid
                    """.trimIndent(),
                    arrayOf<Any>(
                        accountId, pnJid,
                        lidContact.fullName, lidContact.firstName, lidContact.pushName, lidContact.businessName,
                        if (lidContact.isOnline) 1 else 0, lidContact.lastSeenMs,
                        lidJid, pnJid
                    )
                )
                db.execSQL(
                    "DELETE FROM contacts WHERE account_id = ? AND contact_jid = ?",
                    arrayOf<Any>(accountId, lidJid)
                )
            } else {
                db.execSQL(
                    "UPDATE contacts SET lid_jid = ?, pn_jid = ? WHERE account_id = ? AND contact_jid = ?",
                    arrayOf<Any>(lidJid, pnJid, accountId, pnJid)
                )
            }

            // Check if a split @lid chat row exists for this same account
            val lidChat = getChatSummaryInternal(accountId, lidJid)
            if (lidChat != null) {
                mergedChats = true
                val pnChat = getChatSummaryInternal(accountId, pnJid)
                val bestTitle = IdentityResolver.cleanDisplayTitle(
                    explicitNames = listOfNotNull(pnChat?.title, lidChat.title),
                    canonicalJid = pnJid,
                    verifiedPnJid = pnJid,
                    isGroup = false
                )
                val mergedUnread = (pnChat?.unreadCount ?: 0) + lidChat.unreadCount
                val mergedPinned = if ((pnChat?.isPinned == true) || lidChat.isPinned) 1 else 0
                val mergedPinnedAt = maxOf(pnChat?.pinnedAtMs ?: 0L, lidChat.pinnedAtMs)
                val mergedArchived = if ((pnChat?.isArchived == true) && lidChat.isArchived) 1 else 0
                val mergedMuted = if ((pnChat?.isMuted == true) || lidChat.isMuted) 1 else 0

                db.execSQL(
                    """
                    INSERT INTO chats (
                        account_id, chat_jid, composite_key, title, is_group,
                        last_message_id, last_message_preview, last_message_ts_ms, last_sender_name,
                        unread_count, is_pinned, pinned_at_ms, is_archived, is_muted, lid_jid, pn_jid
                    ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, chat_jid) DO UPDATE SET
                        title = ?,
                        unread_count = ?,
                        is_pinned = ?,
                        pinned_at_ms = ?,
                        is_archived = ?,
                        is_muted = ?,
                        lid_jid = ?,
                        pn_jid = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        accountId, pnJid, "$accountId:$pnJid", bestTitle,
                        lidChat.lastMessageId, lidChat.lastMessagePreview, lidChat.lastMessageTsMs, lidChat.lastSenderName,
                        mergedUnread, mergedPinned, mergedPinnedAt, mergedArchived, mergedMuted, lidJid, pnJid,
                        bestTitle, mergedUnread, mergedPinned, mergedPinnedAt, mergedArchived, mergedMuted, lidJid, pnJid
                    )
                )

                // Move all messages & media from @lid chat into canonical @s.whatsapp.net chat
                val msgIds = mutableListOf<String>()
                db.query(
                    "SELECT message_id FROM messages WHERE account_id = ? AND chat_jid = ?",
                    arrayOf<Any>(accountId, lidJid)
                ).use { c ->
                    while (c.moveToNext()) msgIds.add(c.getString(0))
                }

                for (mId in msgIds) {
                    val alreadyInPn = db.query(
                        "SELECT 1 FROM messages WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
                        arrayOf<Any>(accountId, pnJid, mId)
                    ).use { it.moveToFirst() }

                    if (!alreadyInPn) {
                        db.execSQL(
                            """
                            INSERT INTO messages (
                                account_id, chat_jid, message_id, composite_key, sender_jid, sender_name,
                                is_from_me, is_group, text_body, media_type, quoted_message_id, quoted_sender_jid,
                                quoted_text, reactions_summary, is_deleted, is_edited, status, timestamp_ms,
                                raw_chat_jid, client_msg_id, transmission_ack, error_reason
                            )
                            SELECT account_id, ?, message_id, ?, sender_jid, sender_name,
                                   is_from_me, is_group, text_body, media_type, quoted_message_id, quoted_sender_jid,
                                   quoted_text, reactions_summary, is_deleted, is_edited, status, timestamp_ms,
                                   CASE WHEN raw_chat_jid != '' THEN raw_chat_jid ELSE chat_jid END,
                                   client_msg_id, transmission_ack, error_reason
                              FROM messages
                             WHERE account_id = ? AND chat_jid = ? AND message_id = ?
                            """.trimIndent(),
                            arrayOf<Any>(pnJid, "$accountId:$pnJid:$mId", accountId, lidJid, mId)
                        )
                        db.execSQL(
                            """
                            INSERT OR IGNORE INTO message_media (
                                account_id, chat_jid, message_id, media_type, local_path, mime_type,
                                file_name, file_size, duration_sec, is_voice_note, direct_path,
                                media_key_b64, file_sha256_b64, file_enc_sha256_b64, download_state, error_message
                            )
                            SELECT account_id, ?, message_id, media_type, local_path, mime_type,
                                   file_name, file_size, duration_sec, is_voice_note, direct_path,
                                   media_key_b64, file_sha256_b64, file_enc_sha256_b64, download_state, error_message
                              FROM message_media
                             WHERE account_id = ? AND chat_jid = ? AND message_id = ?
                            """.trimIndent(),
                            arrayOf<Any>(pnJid, accountId, lidJid, mId)
                        )
                    }
                }

                // Remove the superseded @lid chat row (cascades its moved @lid rows cleanly)
                db.execSQL(
                    "DELETE FROM chats WHERE account_id = ? AND chat_jid = ?",
                    arrayOf<Any>(accountId, lidJid)
                )
                recomputeChatPreviewInternal(accountId, pnJid)
            } else {
                db.execSQL(
                    "UPDATE chats SET lid_jid = ?, pn_jid = ? WHERE account_id = ? AND chat_jid = ?",
                    arrayOf<Any>(lidJid, pnJid, accountId, pnJid)
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        if (mergedChats) {
            _jidMergeRedirects.tryEmit(Triple(accountId, lidJid, pnJid))
            bumpContactsGroups()
            bumpConversation(accountId, pnJid, alsoInbox = true)
        } else if (lidContact != null) {
            bumpContactsGroups()
        }
        return mergedChats
    }

    private fun recomputeChatPreviewInternal(accountId: Int, chatJid: String) {
        db.query(
            """
            SELECT message_id, text_body, media_type, is_deleted, sender_name, timestamp_ms
              FROM messages
             WHERE account_id = ? AND chat_jid = ?
             ORDER BY timestamp_ms DESC, rowid DESC
             LIMIT 1
            """.trimIndent(),
            arrayOf<Any>(accountId, chatJid)
        ).use { c ->
            if (c.moveToFirst()) {
                val mId = c.getString(0)
                val text = c.getString(1)
                val mType = c.getString(2)
                val isDel = c.getInt(3) != 0
                val sender = c.getString(4)
                val ts = c.getLong(5)
                val preview = buildPreviewText(text, mType, isDel)
                db.execSQL(
                    """
                    UPDATE chats
                       SET last_message_id = ?, last_message_preview = ?, last_message_ts_ms = ?, last_sender_name = ?
                     WHERE account_id = ? AND chat_jid = ?
                    """.trimIndent(),
                    arrayOf<Any>(mId, preview, ts, sender, accountId, chatJid)
                )
            }
        }
    }

    // =========================================================================
    // ACCOUNTS & SESSIONS
    // =========================================================================

    fun getAccounts(): List<AccountRecord> {
        val out = mutableListOf<AccountRecord>()
        db.query(
            "SELECT account_id, label, color_hex, jid, phone, push_name, state, error_message, reconnect_attempt, has_saved_session, updated_at_ms FROM accounts ORDER BY account_id ASC"
        ).use { c ->
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
        bumpAccounts()
        bumpInbox()
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

        if (existing.status == status &&
            existing.jid == nextJid &&
            existing.phone == nextPhone &&
            existing.pushName == nextPush &&
            existing.errorMessage == errorMessage &&
            existing.reconnectAttempt == reconnectAttempt &&
            existing.hasSavedSession == nextSaved
        ) {
            return
        }

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
        bumpAccounts()
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
        bumpAccounts()
    }

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
            db.execSQL("DELETE FROM identity_mappings WHERE account_id = ?", args)
            db.execSQL("DELETE FROM inbound_event_journal WHERE account_id = ?", args)
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
        bumpAccounts()
        bumpInbox()
        bumpContactsGroups()
    }

    // =========================================================================
    // CONTACTS & PRESENCE
    // =========================================================================

    fun upsertContacts(accountId: Int, items: List<ContactRecord>) {
        requireValidAccount(accountId)
        if (items.isEmpty()) return
        db.beginTransaction()
        try {
            for (item in items) {
                if (item.contactJid.isBlank()) continue
                if (item.lidJid.isNotBlank() && item.pnJid.isNotBlank()) {
                    recordIdentityMappingAndMerge(accountId, item.lidJid, item.pnJid, "contacts")
                }
                val canonicalJid = resolveCanonicalChatJid(accountId, item.contactJid)
                val resolvedPn = item.pnJid.ifBlank { if (IdentityResolver.isPhoneJid(canonicalJid)) canonicalJid else "" }
                val resolvedLid = item.lidJid.ifBlank {
                    if (IdentityResolver.isLidJid(canonicalJid)) canonicalJid else getLidForPn(accountId, canonicalJid)
                }

                db.execSQL(
                    """
                    INSERT INTO contacts (account_id, contact_jid, full_name, first_name, push_name, business_name, is_online, last_seen_ms, lid_jid, pn_jid)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, contact_jid) DO UPDATE SET
                        full_name = CASE WHEN excluded.full_name != '' THEN excluded.full_name ELSE contacts.full_name END,
                        first_name = CASE WHEN excluded.first_name != '' THEN excluded.first_name ELSE contacts.first_name END,
                        push_name = CASE WHEN excluded.push_name != '' THEN excluded.push_name ELSE contacts.push_name END,
                        business_name = CASE WHEN excluded.business_name != '' THEN excluded.business_name ELSE contacts.business_name END,
                        lid_jid = CASE WHEN excluded.lid_jid != '' THEN excluded.lid_jid ELSE contacts.lid_jid END,
                        pn_jid = CASE WHEN excluded.pn_jid != '' THEN excluded.pn_jid ELSE contacts.pn_jid END
                    """.trimIndent(),
                    arrayOf<Any>(
                        accountId,
                        canonicalJid,
                        item.fullName,
                        item.firstName,
                        item.pushName,
                        item.businessName,
                        if (item.isOnline) 1 else 0,
                        item.lastSeenMs,
                        resolvedLid,
                        resolvedPn
                    )
                )

                val explicitTitle = listOf(item.fullName, item.pushName, item.businessName, item.firstName)
                    .firstOrNull { it.isNotBlank() }
                if (!explicitTitle.isNullOrBlank() && !IdentityResolver.isGroupJid(canonicalJid)) {
                    db.execSQL(
                        """
                        UPDATE chats SET title = ?
                         WHERE account_id = ? AND chat_jid = ?
                           AND (title = '' OR title LIKE 'WhatsApp Contact%' OR title LIKE '+%' OR title = SUBSTR(chat_jid, 1, INSTR(chat_jid, '@') - 1) OR title = chat_jid)
                        """.trimIndent(),
                        arrayOf<Any>(explicitTitle, accountId, canonicalJid)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpContactsGroups()
        bumpInbox()
    }

    fun updatePresence(accountId: Int, contactJid: String, isOnline: Boolean, lastSeenMs: Long) {
        requireValidAccount(accountId)
        val canonical = resolveCanonicalChatJid(accountId, contactJid)
        if (canonical.isBlank()) return
        db.execSQL(
            """
            INSERT INTO contacts (account_id, contact_jid, is_online, last_seen_ms)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(account_id, contact_jid) DO UPDATE SET
                is_online = excluded.is_online,
                last_seen_ms = CASE WHEN excluded.last_seen_ms > 0 THEN excluded.last_seen_ms ELSE contacts.last_seen_ms END
            """.trimIndent(),
            arrayOf<Any>(accountId, canonical, if (isOnline) 1 else 0, lastSeenMs)
        )
        // Scoped only to contacts/presence — NEVER invalidates message list!
        bumpContactsGroups()
    }

    fun getContact(accountId: Int, contactJid: String): ContactRecord? {
        requireValidAccount(accountId)
        val canonical = resolveCanonicalChatJid(accountId, contactJid)
        db.query(
            """
            SELECT account_id, contact_jid, full_name, first_name, push_name, business_name,
                   is_online, last_seen_ms, lid_jid, pn_jid
              FROM contacts
             WHERE account_id = ? AND contact_jid = ?
             LIMIT 1
            """.trimIndent(),
            arrayOf<Any>(accountId, canonical)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ContactRecord(
                accountId = c.getInt(0),
                contactJid = c.getString(1),
                fullName = c.getString(2),
                firstName = c.getString(3),
                pushName = c.getString(4),
                businessName = c.getString(5),
                isOnline = c.getInt(6) != 0,
                lastSeenMs = c.getLong(7),
                lidJid = c.getString(8),
                pnJid = c.getString(9)
            )
        }
    }

    fun getContacts(
        accountFilter: Int? = null,
        searchQuery: String = "",
        hideUnidentifiedLid: Boolean = false
    ): List<ContactRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (accountFilter != null) {
            requireValidAccount(accountFilter)
            clauses.add("account_id = ?")
            args.add(accountFilter)
        }
        if (hideUnidentifiedLid) {
            // Hide nameless raw @lid protocol records that have neither name nor verified phone
            clauses.add("NOT (contact_jid LIKE '%@lid' AND pn_jid = '' AND full_name = '' AND push_name = '' AND business_name = '' AND first_name = '')")
        }
        if (searchQuery.isNotBlank()) {
            val pat = "%${searchQuery.trim()}%"
            clauses.add("(full_name LIKE ? OR push_name LIKE ? OR business_name LIKE ? OR pn_jid LIKE ? OR contact_jid LIKE ?)")
            args.addAll(listOf(pat, pat, pat, pat, pat))
        }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val sql = """
            SELECT account_id, contact_jid, full_name, first_name, push_name, business_name,
                   is_online, last_seen_ms, lid_jid, pn_jid
              FROM contacts
              $where
             ORDER BY COALESCE(NULLIF(full_name,''), NULLIF(push_name,''), NULLIF(business_name,''), contact_jid) COLLATE NOCASE ASC
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
                        lastSeenMs = c.getLong(7),
                        lidJid = c.getString(8),
                        pnJid = c.getString(9)
                    )
                )
            }
        }
        return out
    }

    // =========================================================================
    // CHATS, OUTGOING LIFECYCLE & MESSAGES
    // =========================================================================

    fun ensureChatExists(
        accountId: Int,
        chatJidRaw: String,
        preferredTitle: String = "",
        isGroup: Boolean = IdentityResolver.isGroupJid(chatJidRaw)
    ): String {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        val composite = "$accountId:$chatJid"
        val pnJid = if (IdentityResolver.isPhoneJid(chatJid)) chatJid else ""
        val lidJid = if (IdentityResolver.isLidJid(chatJid)) chatJid else getLidForPn(accountId, chatJid)
        val fallbackTitle = preferredTitle.ifBlank { resolveDisplayTitleInternal(accountId, chatJid, isGroup) }
        db.execSQL(
            """
            INSERT INTO chats (account_id, chat_jid, composite_key, title, is_group, last_message_ts_ms, lid_jid, pn_jid)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, chat_jid) DO UPDATE SET
                title = CASE WHEN ? != '' THEN ? ELSE chats.title END,
                lid_jid = CASE WHEN chats.lid_jid != '' THEN chats.lid_jid ELSE excluded.lid_jid END,
                pn_jid = CASE WHEN chats.pn_jid != '' THEN chats.pn_jid ELSE excluded.pn_jid END
            """.trimIndent(),
            arrayOf<Any>(
                accountId,
                chatJid,
                composite,
                fallbackTitle,
                if (isGroup) 1 else 0,
                System.currentTimeMillis(),
                lidJid,
                pnJid,
                preferredTitle,
                preferredTitle
            )
        )
        bumpInbox()
        return chatJid
    }

    private fun resolveDisplayTitleInternal(accountId: Int, chatJid: String, isGroup: Boolean): String {
        if (isGroup) {
            db.query(
                "SELECT name FROM groups WHERE account_id = ? AND group_jid = ?",
                arrayOf<Any>(accountId, chatJid)
            ).use { c ->
                if (c.moveToFirst() && c.getString(0).isNotBlank()) return c.getString(0)
            }
            return "WhatsApp Group"
        }
        var verifiedPn = if (IdentityResolver.isPhoneJid(chatJid)) chatJid else ""
        val names = mutableListOf<String>()
        db.query(
            "SELECT full_name, push_name, business_name, first_name, pn_jid FROM contacts WHERE account_id = ? AND contact_jid = ?",
            arrayOf<Any>(accountId, chatJid)
        ).use { c ->
            if (c.moveToFirst()) {
                names.add(c.getString(0))
                names.add(c.getString(1))
                names.add(c.getString(2))
                names.add(c.getString(3))
                if (verifiedPn.isBlank()) verifiedPn = c.getString(4)
            }
        }
        return IdentityResolver.cleanDisplayTitle(names, chatJid, verifiedPn, isGroup = false)
    }

    private fun buildPreviewText(textBody: String, mediaType: String, isDeleted: Boolean): String = when {
        isDeleted -> "This message was deleted"
        textBody.isNotBlank() && mediaType.isNotBlank() -> "[${mediaType.replaceFirstChar { it.uppercase() }}] $textBody"
        textBody.isNotBlank() -> textBody
        mediaType == "voice" -> "[Voice note]"
        mediaType == "image" -> "[Photo]"
        mediaType == "video" -> "[Video]"
        mediaType == "audio" -> "[Audio]"
        mediaType == "document" -> "[Document]"
        mediaType == "sticker" -> "[Sticker]"
        mediaType.isNotBlank() -> "[${mediaType.replaceFirstChar { it.uppercase() }}]"
        else -> ""
    }

    /**
     * Inserts or updates a message strictly scoped to (account_id, canonical_chat_jid, message_id)
     * and atomically updates the owning conversation preview & unread count.
     * Monotonically protects delivery status so 'delivered'/'read' is NEVER downgraded to 'sent'/'pending'.
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

        val canonicalChatJid = resolveCanonicalChatJid(record.accountId, record.chatJid)
        val pnJid = if (IdentityResolver.isPhoneJid(canonicalChatJid)) canonicalChatJid else ""
        val lidJid = if (IdentityResolver.isLidJid(canonicalChatJid)) {
            canonicalChatJid
        } else {
            getLidForPn(record.accountId, canonicalChatJid)
        }

        var insertedNew = false
        db.beginTransaction()
        try {
            val chatComp = "${record.accountId}:$canonicalChatJid"
            val resolvedTitle = IdentityResolver.cleanDisplayTitle(
                explicitNames = listOf(
                    chatTitleHint,
                    if (!record.isGroup && !record.isFromMe) record.senderName else "",
                    resolveDisplayTitleInternal(record.accountId, canonicalChatJid, record.isGroup)
                ),
                canonicalJid = canonicalChatJid,
                verifiedPnJid = pnJid,
                isGroup = record.isGroup
            )

            db.execSQL(
                """
                INSERT INTO chats (account_id, chat_jid, composite_key, title, is_group, last_message_ts_ms, lid_jid, pn_jid)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id, chat_jid) DO UPDATE SET
                    lid_jid = CASE WHEN chats.lid_jid != '' THEN chats.lid_jid ELSE excluded.lid_jid END,
                    pn_jid = CASE WHEN chats.pn_jid != '' THEN chats.pn_jid ELSE excluded.pn_jid END
                """.trimIndent(),
                arrayOf<Any>(
                    record.accountId,
                    canonicalChatJid,
                    chatComp,
                    resolvedTitle,
                    if (record.isGroup) 1 else 0,
                    record.timestampMs,
                    lidJid,
                    pnJid
                )
            )

            var existingStatus = ""
            val existed = db.query(
                "SELECT status FROM messages WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
                arrayOf<Any>(record.accountId, canonicalChatJid, record.messageId)
            ).use { c ->
                if (c.moveToFirst()) {
                    existingStatus = c.getString(0)
                    true
                } else false
            }

            insertedNew = !existed
            val finalStatus = if (existed && IdentityResolver.statusRank(existingStatus) > IdentityResolver.statusRank(record.status) && record.status != "failed") {
                existingStatus
            } else {
                record.status
            }

            val msgComp = "${record.accountId}:$canonicalChatJid:${record.messageId}"
            db.execSQL(
                """
                INSERT INTO messages (
                    account_id, chat_jid, message_id, composite_key, sender_jid, sender_name,
                    is_from_me, is_group, text_body, media_type, quoted_message_id, quoted_sender_jid,
                    quoted_text, reactions_summary, is_deleted, is_edited, status, timestamp_ms,
                    raw_chat_jid, client_msg_id, transmission_ack, error_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(account_id, chat_jid, message_id) DO UPDATE SET
                    text_body = CASE WHEN excluded.text_body != '' THEN excluded.text_body ELSE messages.text_body END,
                    media_type = CASE WHEN excluded.media_type != '' THEN excluded.media_type ELSE messages.media_type END,
                    sender_name = CASE WHEN excluded.sender_name != '' THEN excluded.sender_name ELSE messages.sender_name END,
                    is_edited = MAX(messages.is_edited, excluded.is_edited),
                    is_deleted = MAX(messages.is_deleted, excluded.is_deleted),
                    status = ?,
                    timestamp_ms = CASE WHEN excluded.timestamp_ms > 0 THEN excluded.timestamp_ms ELSE messages.timestamp_ms END,
                    transmission_ack = MAX(messages.transmission_ack, excluded.transmission_ack),
                    error_reason = excluded.error_reason
                """.trimIndent(),
                arrayOf<Any>(
                    record.accountId,
                    canonicalChatJid,
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
                    finalStatus,
                    record.timestampMs,
                    record.rawChatJid.ifBlank { record.chatJid },
                    record.clientMessageId.ifBlank { record.messageId },
                    if (record.transmissionAck) 1 else 0,
                    record.errorReason,
                    finalStatus
                )
            )

            record.media?.let { m ->
                db.execSQL(
                    """
                    INSERT INTO message_media (
                        account_id, chat_jid, message_id, media_type, local_path, mime_type,
                        file_name, file_size, duration_sec, is_voice_note, direct_path,
                        media_key_b64, file_sha256_b64, file_enc_sha256_b64, download_state, error_message
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(account_id, chat_jid, message_id) DO UPDATE SET
                        local_path = CASE WHEN excluded.local_path != '' THEN excluded.local_path ELSE message_media.local_path END,
                        download_state = CASE WHEN excluded.local_path != '' THEN 'ready' ELSE message_media.download_state END,
                        direct_path = CASE WHEN excluded.direct_path != '' THEN excluded.direct_path ELSE message_media.direct_path END,
                        media_key_b64 = CASE WHEN excluded.media_key_b64 != '' THEN excluded.media_key_b64 ELSE message_media.media_key_b64 END
                    """.trimIndent(),
                    arrayOf<Any>(
                        record.accountId,
                        canonicalChatJid,
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

            val preview = buildPreviewText(record.textBody, record.mediaType, record.isDeleted)
            val unreadDelta = if (insertedNew && incrementUnread && !record.isFromMe) 1 else 0

            if (explicitUnreadCount != null) {
                db.execSQL(
                    """
                    UPDATE chats
                       SET title = CASE WHEN ? != '' AND (title = '' OR title LIKE 'WhatsApp Contact%' OR title LIKE '+%') THEN ? ELSE title END,
                           last_message_id = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_id END,
                           last_message_preview = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_preview END,
                           last_sender_name = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_sender_name END,
                           last_message_ts_ms = MAX(last_message_ts_ms, ?),
                           unread_count = ?
                     WHERE account_id = ? AND chat_jid = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        resolvedTitle, resolvedTitle,
                        record.timestampMs, record.messageId,
                        record.timestampMs, preview,
                        record.timestampMs, record.senderName,
                        record.timestampMs,
                        explicitUnreadCount,
                        record.accountId, canonicalChatJid
                    )
                )
            } else {
                db.execSQL(
                    """
                    UPDATE chats
                       SET title = CASE WHEN ? != '' AND (title = '' OR title LIKE 'WhatsApp Contact%' OR title LIKE '+%') THEN ? ELSE title END,
                           last_message_id = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_id END,
                           last_message_preview = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_message_preview END,
                           last_sender_name = CASE WHEN ? >= last_message_ts_ms THEN ? ELSE last_sender_name END,
                           last_message_ts_ms = MAX(last_message_ts_ms, ?),
                           unread_count = unread_count + ?,
                           is_archived = CASE WHEN ? > 0 AND is_muted = 0 THEN 0 ELSE is_archived END,
                           is_typing = 0,
                           typing_sender = ''
                     WHERE account_id = ? AND chat_jid = ?
                    """.trimIndent(),
                    arrayOf<Any>(
                        resolvedTitle, resolvedTitle,
                        record.timestampMs, record.messageId,
                        record.timestampMs, preview,
                        record.timestampMs, record.senderName,
                        record.timestampMs,
                        unreadDelta,
                        unreadDelta,
                        record.accountId, canonicalChatJid
                    )
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        bumpConversation(record.accountId, canonicalChatJid, alsoInbox = true)
        return insertedNew
    }

    /**
     * Reconciles a pre-persisted outgoing Pending message after WhatsApp server ACK.
     * Monotonically transitions 'pending'/'failed' -> 'sent' without overwriting 'delivered'/'read'.
     */
    fun markOutgoingMessageSent(
        accountId: Int,
        chatJidRaw: String,
        clientMessageId: String,
        serverMessageId: String,
        serverTimestampMs: Long
    ) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        val ts = if (serverTimestampMs > 0L) serverTimestampMs else System.currentTimeMillis()
        db.beginTransaction()
        try {
            if (serverMessageId.isNotBlank() && serverMessageId != clientMessageId) {
                db.execSQL(
                    """
                    UPDATE messages
                       SET message_id = ?,
                           composite_key = ?,
                           status = CASE WHEN status IN ('pending', 'failed') THEN 'sent' ELSE status END,
                           timestamp_ms = ?,
                           transmission_ack = 1,
                           error_reason = ''
                     WHERE account_id = ? AND chat_jid = ? AND message_id = ?
                    """.trimIndent(),
                    arrayOf<Any>(serverMessageId, "$accountId:$chatJid:$serverMessageId", ts, accountId, chatJid, clientMessageId)
                )
            } else {
                db.execSQL(
                    """
                    UPDATE messages
                       SET status = CASE WHEN status IN ('pending', 'failed') THEN 'sent' ELSE status END,
                           timestamp_ms = ?,
                           transmission_ack = 1,
                           error_reason = ''
                     WHERE account_id = ? AND chat_jid = ? AND (message_id = ? OR client_msg_id = ?)
                    """.trimIndent(),
                    arrayOf<Any>(ts, accountId, chatJid, clientMessageId, clientMessageId)
                )
            }
            db.execSQL(
                """
                UPDATE chats
                   SET last_message_ts_ms = MAX(last_message_ts_ms, ?)
                 WHERE account_id = ? AND chat_jid = ?
                """.trimIndent(),
                arrayOf<Any>(ts, accountId, chatJid)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    /**
     * Marks a Pending outgoing message as 'failed' ONLY when network transmission did NOT complete.
     */
    fun markOutgoingMessageFailed(
        accountId: Int,
        chatJidRaw: String,
        messageId: String,
        reason: String
    ) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.execSQL(
            """
            UPDATE messages
               SET status = 'failed',
                   error_reason = ?
             WHERE account_id = ? AND chat_jid = ? AND message_id = ? AND transmission_ack = 0
            """.trimIndent(),
            arrayOf<Any>(reason.take(240), accountId, chatJid, messageId)
        )
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    fun resetFailedMessageToPending(accountId: Int, chatJidRaw: String, messageId: String): MessageRecord? {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.execSQL(
            """
            UPDATE messages
               SET status = 'pending',
                   error_reason = ''
             WHERE account_id = ? AND chat_jid = ? AND message_id = ? AND transmission_ack = 0
            """.trimIndent(),
            arrayOf<Any>(accountId, chatJid, messageId)
        )
        bumpConversation(accountId, chatJid, alsoInbox = false)
        return getMessageById(accountId, chatJid, messageId)
    }

    /**
     * Crash/restart recovery: transitions stale un-ACKed 'pending' messages from a killed process
     * to 'failed' so the user can tap Retry without any message hanging in 'pending' forever.
     */
    fun recoverInterruptedPendingMessages(staleThresholdMs: Long = System.currentTimeMillis() - 5_000L): Int {
        val stmt = db.compileStatement(
            """
            UPDATE messages
               SET status = 'failed',
                   error_reason = 'App interrupted before delivery confirmation — tap Retry'
             WHERE status = 'pending' AND transmission_ack = 0 AND timestamp_ms <= ?
            """.trimIndent()
        )
        stmt.bindLong(1, staleThresholdMs)
        val updated = stmt.executeUpdateDelete()
        if (updated > 0) {
            bumpInbox()
        }
        return updated
    }

    fun getMessageById(accountId: Int, chatJidRaw: String, messageId: String): MessageRecord? {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        val list = queryMessagesInternal(
            whereClause = "WHERE m.account_id = ? AND m.chat_jid = ? AND m.message_id = ?",
            args = arrayOf(accountId, chatJid, messageId),
            limit = 1
        )
        return list.firstOrNull()
    }

    // =========================================================================
    // CONVERSATION QUERIES (FIXED NEWEST-WINDOW + PAGINATION + SEARCH)
    // =========================================================================

    private fun readChatSummaryRow(c: android.database.Cursor): ChatSummaryRecord = ChatSummaryRecord(
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
        typingSender = c.getString(11),
        isPinned = c.getInt(12) != 0,
        pinnedAtMs = c.getLong(13),
        isArchived = c.getInt(14) != 0,
        isMuted = c.getInt(15) != 0,
        markedUnread = c.getInt(16) != 0,
        lidJid = c.getString(17),
        pnJid = c.getString(18)
    )

    private val chatSelectColumns = """
        account_id, chat_jid, composite_key, title, is_group, last_message_id,
        last_message_preview, last_message_ts_ms, last_sender_name, unread_count,
        is_typing, typing_sender, is_pinned, pinned_at_ms, is_archived,
        is_muted, marked_unread, lid_jid, pn_jid
    """.trimIndent()

    fun getUnifiedInboxChats(
        accountFilter: Int? = null,
        unreadOnly: Boolean = false,
        chatTypeFilter: String = "all", // "all", "groups", "direct", "archived"
        searchQuery: String = "",
        includeArchived: Boolean = false
    ): List<ChatSummaryRecord> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (accountFilter != null) {
            requireValidAccount(accountFilter)
            clauses.add("account_id = ?")
            args.add(accountFilter)
        }
        if (chatTypeFilter == "archived") {
            clauses.add("is_archived = 1")
        } else if (!includeArchived && searchQuery.isBlank()) {
            clauses.add("is_archived = 0")
        }
        if (unreadOnly) {
            clauses.add("(unread_count > 0 OR marked_unread = 1)")
        }
        when (chatTypeFilter) {
            "groups" -> clauses.add("is_group = 1")
            "direct" -> clauses.add("is_group = 0")
        }
        if (searchQuery.isNotBlank()) {
            val pat = "%${searchQuery.trim()}%"
            clauses.add("(title LIKE ? OR last_message_preview LIKE ? OR pn_jid LIKE ? OR chat_jid LIKE ?)")
            args.addAll(listOf(pat, pat, pat, pat))
        }

        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val sql = """
            SELECT $chatSelectColumns
              FROM chats
              $where
             ORDER BY is_pinned DESC, pinned_at_ms DESC, last_message_ts_ms DESC
             LIMIT 500
        """.trimIndent()

        val out = mutableListOf<ChatSummaryRecord>()
        db.query(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(readChatSummaryRow(c))
            }
        }
        return out
    }

    fun getArchivedChatCount(accountFilter: Int? = null): Int {
        val sql = if (accountFilter != null) {
            requireValidAccount(accountFilter)
            "SELECT COUNT(*) FROM chats WHERE is_archived = 1 AND account_id = ?"
        } else {
            "SELECT COUNT(*) FROM chats WHERE is_archived = 1"
        }
        val args = if (accountFilter != null) arrayOf<Any>(accountFilter) else emptyArray()
        db.query(sql, args).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    private fun getChatSummaryInternal(accountId: Int, exactChatJid: String): ChatSummaryRecord? {
        db.query(
            "SELECT $chatSelectColumns FROM chats WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(accountId, exactChatJid)
        ).use { c ->
            return if (c.moveToFirst()) readChatSummaryRow(c) else null
        }
    }

    fun getChatSummary(accountId: Int, chatJidRaw: String): ChatSummaryRecord? {
        requireValidAccount(accountId)
        val canonical = resolveCanonicalChatJid(accountId, chatJidRaw)
        return getChatSummaryInternal(accountId, canonical)
    }

    fun getConversationMessageCount(accountId: Int, chatJidRaw: String): Int {
        requireValidAccount(accountId)
        val canonical = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.query(
            "SELECT COUNT(*) FROM messages WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(accountId, canonical)
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * Returns the NEWEST [limit] messages for (accountId, canonicalChatJid) in chronological ASC order.
     * Fixes v1.0.0's critical bug where 'ORDER BY timestamp_ms ASC LIMIT 400' hid message #401+!
     */
    fun getConversationMessages(
        accountId: Int,
        chatJidRaw: String,
        limit: Int = 300
    ): List<MessageRecord> {
        requireValidAccount(accountId)
        val canonical = resolveCanonicalChatJid(accountId, chatJidRaw)
        val safeLimit = limit.coerceIn(1, 5000)
        return queryMessagesNewestWindow(accountId, canonical, safeLimit)
    }

    fun searchConversationMessages(
        accountId: Int,
        chatJidRaw: String,
        query: String
    ): List<MessageRecord> {
        requireValidAccount(accountId)
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val canonical = resolveCanonicalChatJid(accountId, chatJidRaw)
        return queryMessagesInternal(
            whereClause = "WHERE m.account_id = ? AND m.chat_jid = ? AND m.is_deleted = 0 AND m.text_body LIKE ?",
            args = arrayOf(accountId, canonical, "%$trimmed%"),
            limit = 200
        )
    }

    fun searchMessagesGlobal(
        query: String,
        accountFilter: Int? = null,
        limit: Int = 80
    ): List<GlobalMessageSearchHit> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        val args = mutableListOf<Any>()
        val acctClause = if (accountFilter != null) {
            requireValidAccount(accountFilter)
            args.add(accountFilter)
            "m.account_id = ? AND"
        } else ""
        args.add("%$trimmed%")
        args.add(limit.coerceIn(1, 200))

        val sql = """
            SELECT m.account_id, m.chat_jid, COALESCE(c.title, m.chat_jid), m.message_id,
                   m.sender_name, m.text_body, m.timestamp_ms, m.is_from_me
              FROM messages m
              LEFT JOIN chats c ON c.account_id = m.account_id AND c.chat_jid = m.chat_jid
             WHERE $acctClause m.is_deleted = 0 AND m.text_body LIKE ?
             ORDER BY m.timestamp_ms DESC
             LIMIT ?
        """.trimIndent()

        val hits = mutableListOf<GlobalMessageSearchHit>()
        db.query(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val chatJid = c.getString(1)
                val rawTitle = c.getString(2)
                hits.add(
                    GlobalMessageSearchHit(
                        accountId = c.getInt(0),
                        chatJid = chatJid,
                        chatTitle = IdentityResolver.cleanDisplayTitle(listOf(rawTitle), chatJid),
                        messageId = c.getString(3),
                        senderName = c.getString(4),
                        textSnippet = c.getString(5),
                        timestampMs = c.getLong(6),
                        isFromMe = c.getInt(7) != 0
                    )
                )
            }
        }
        return hits
    }

    private fun queryMessagesNewestWindow(accountId: Int, canonicalChatJid: String, limit: Int): List<MessageRecord> {
        val sql = """
            SELECT * FROM (
                SELECT m.account_id, m.chat_jid, m.message_id, m.composite_key, m.sender_jid, m.sender_name,
                       m.is_from_me, m.is_group, m.text_body, m.media_type, m.quoted_message_id, m.quoted_sender_jid,
                       m.quoted_text, m.reactions_summary, m.is_deleted, m.is_edited, m.status, m.timestamp_ms,
                       m.raw_chat_jid, m.client_msg_id, m.transmission_ack, m.error_reason,
                       mm.local_path, mm.mime_type, mm.file_name, mm.file_size, mm.duration_sec,
                       mm.is_voice_note, mm.direct_path, mm.media_key_b64, mm.file_sha256_b64,
                       mm.file_enc_sha256_b64, mm.download_state, mm.error_message,
                       m.rowid AS sort_rowid
                  FROM messages m
                  LEFT JOIN message_media mm
                    ON mm.account_id = m.account_id
                   AND mm.chat_jid = m.chat_jid
                   AND mm.message_id = m.message_id
                 WHERE m.account_id = ? AND m.chat_jid = ?
                 ORDER BY m.timestamp_ms DESC, m.rowid DESC
                 LIMIT ?
            ) sub
            ORDER BY sub.timestamp_ms ASC, sub.sort_rowid ASC
        """.trimIndent()

        val out = ArrayList<MessageRecord>(minOf(limit, 512))
        db.query(sql, arrayOf<Any>(accountId, canonicalChatJid, limit)).use { c ->
            while (c.moveToNext()) {
                out.add(readMessageRow(c))
            }
        }
        return out
    }

    private fun queryMessagesInternal(whereClause: String, args: Array<Any>, limit: Int): List<MessageRecord> {
        val sql = """
            SELECT m.account_id, m.chat_jid, m.message_id, m.composite_key, m.sender_jid, m.sender_name,
                   m.is_from_me, m.is_group, m.text_body, m.media_type, m.quoted_message_id, m.quoted_sender_jid,
                   m.quoted_text, m.reactions_summary, m.is_deleted, m.is_edited, m.status, m.timestamp_ms,
                   m.raw_chat_jid, m.client_msg_id, m.transmission_ack, m.error_reason,
                   mm.local_path, mm.mime_type, mm.file_name, mm.file_size, mm.duration_sec,
                   mm.is_voice_note, mm.direct_path, mm.media_key_b64, mm.file_sha256_b64,
                   mm.file_enc_sha256_b64, mm.download_state, mm.error_message
              FROM messages m
              LEFT JOIN message_media mm
                ON mm.account_id = m.account_id
               AND mm.chat_jid = m.chat_jid
               AND mm.message_id = m.message_id
              $whereClause
             ORDER BY m.timestamp_ms ASC, m.rowid ASC
             LIMIT $limit
        """.trimIndent()
        val out = mutableListOf<MessageRecord>()
        db.query(sql, args).use { c ->
            while (c.moveToNext()) out.add(readMessageRow(c))
        }
        return out
    }

    private fun readMessageRow(c: android.database.Cursor): MessageRecord {
        val acct = c.getInt(0)
        val cJid = c.getString(1)
        val mId = c.getString(2)
        val mType = c.getString(9)
        val media = if (mType.isNotBlank()) {
            MessageMediaRecord(
                accountId = acct,
                chatJid = cJid,
                messageId = mId,
                mediaType = mType,
                localPath = c.getString(22) ?: "",
                mimeType = c.getString(23) ?: "",
                fileName = c.getString(24) ?: "",
                fileSize = if (c.isNull(25)) 0L else c.getLong(25),
                durationSec = if (c.isNull(26)) 0 else c.getInt(26),
                isVoiceNote = !c.isNull(27) && c.getInt(27) != 0,
                directPath = c.getString(28) ?: "",
                mediaKeyB64 = c.getString(29) ?: "",
                fileSha256B64 = c.getString(30) ?: "",
                fileEncSha256B64 = c.getString(31) ?: "",
                downloadState = c.getString(32) ?: "none",
                errorMessage = c.getString(33) ?: ""
            )
        } else null

        return MessageRecord(
            accountId = acct,
            chatJid = cJid,
            messageId = mId,
            compositeKey = c.getString(3),
            senderJid = c.getString(4),
            senderName = c.getString(5),
            isFromMe = c.getInt(6) != 0,
            isGroup = c.getInt(7) != 0,
            textBody = c.getString(8),
            mediaType = mType,
            quotedMessageId = c.getString(10),
            quotedSenderJid = c.getString(11),
            quotedText = c.getString(12),
            reactionsSummary = c.getString(13),
            isDeleted = c.getInt(14) != 0,
            isEdited = c.getInt(15) != 0,
            status = c.getString(16),
            timestampMs = c.getLong(17),
            media = media,
            rawChatJid = c.getString(18) ?: "",
            clientMessageId = c.getString(19) ?: "",
            transmissionAck = c.getInt(20) != 0,
            errorReason = c.getString(21) ?: ""
        )
    }

    // =========================================================================
    // CHAT ACTIONS (PIN, ARCHIVE, MUTE, MARK UNREAD, CLEAR UNREAD, LOCAL DELETE)
    // =========================================================================

    fun setChatPinned(accountId: Int, chatJidRaw: String, pinned: Boolean) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.execSQL(
            "UPDATE chats SET is_pinned = ?, pinned_at_ms = ? WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(if (pinned) 1 else 0, if (pinned) System.currentTimeMillis() else 0L, accountId, chatJid)
        )
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    fun setChatArchived(accountId: Int, chatJidRaw: String, archived: Boolean) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.execSQL(
            "UPDATE chats SET is_archived = ? WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(if (archived) 1 else 0, accountId, chatJid)
        )
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    fun setChatMuted(accountId: Int, chatJidRaw: String, muted: Boolean) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.execSQL(
            "UPDATE chats SET is_muted = ? WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(if (muted) 1 else 0, accountId, chatJid)
        )
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    fun setChatMarkedUnread(accountId: Int, chatJidRaw: String, markedUnread: Boolean) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.execSQL(
            "UPDATE chats SET marked_unread = ?, unread_count = CASE WHEN ? = 0 THEN 0 ELSE unread_count END WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(if (markedUnread) 1 else 0, if (markedUnread) 1 else 0, accountId, chatJid)
        )
        bumpInbox()
    }

    fun clearChatUnread(accountId: Int, chatJidRaw: String): List<Pair<String, String>> {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        val unreadIncoming = mutableListOf<Pair<String, String>>()
        db.query(
            """
            SELECT message_id, sender_jid
              FROM messages
             WHERE account_id = ? AND chat_jid = ? AND is_from_me = 0 AND status != 'read'
             ORDER BY timestamp_ms DESC
             LIMIT 30
            """.trimIndent(),
            arrayOf<Any>(accountId, chatJid)
        ).use { c ->
            while (c.moveToNext()) {
                unreadIncoming.add(c.getString(0) to c.getString(1))
            }
        }
        db.execSQL(
            "UPDATE chats SET unread_count = 0, marked_unread = 0 WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(accountId, chatJid)
        )
        if (unreadIncoming.isNotEmpty()) {
            db.execSQL(
                "UPDATE messages SET status = 'read' WHERE account_id = ? AND chat_jid = ? AND is_from_me = 0",
                arrayOf<Any>(accountId, chatJid)
            )
        }
        bumpInbox()
        return unreadIncoming
    }

    fun deleteMessageLocally(accountId: Int, chatJidRaw: String, messageId: String) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM messages WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
                arrayOf<Any>(accountId, chatJid, messageId)
            )
            recomputeChatPreviewInternal(accountId, chatJid)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    // =========================================================================
    // RECEIPTS, REACTIONS, REVOCATIONS, EDITS & MEDIA DOWNLOAD STATE
    // =========================================================================

    private fun findOwningChatJidByMessageId(accountId: Int, hintChatJid: String, messageId: String): String {
        val canonicalHint = resolveCanonicalChatJid(accountId, hintChatJid)
        if (canonicalHint.isNotBlank()) {
            val foundInHint = db.query(
                "SELECT chat_jid FROM messages WHERE account_id = ? AND chat_jid = ? AND message_id = ? LIMIT 1",
                arrayOf<Any>(accountId, canonicalHint, messageId)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (foundInHint != null) return foundInHint
        }
        // Fallback via idx_messages_acct_msgid in case receipt arrived via @lid before mapping
        db.query(
            "SELECT chat_jid FROM messages WHERE account_id = ? AND message_id = ? LIMIT 1",
            arrayOf<Any>(accountId, messageId)
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return canonicalHint
    }

    fun applyMessageReaction(accountId: Int, chatJidRaw: String, targetMessageId: String, emoji: String) {
        requireValidAccount(accountId)
        val chatJid = findOwningChatJidByMessageId(accountId, chatJidRaw, targetMessageId)
        if (chatJid.isBlank()) return
        db.execSQL(
            "UPDATE messages SET reactions_summary = ? WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
            arrayOf<Any>(emoji, accountId, chatJid, targetMessageId)
        )
        bumpConversation(accountId, chatJid, alsoInbox = false)
    }

    fun markMessageRevoked(accountId: Int, chatJidRaw: String, targetMessageId: String) {
        requireValidAccount(accountId)
        val chatJid = findOwningChatJidByMessageId(accountId, chatJidRaw, targetMessageId)
        if (chatJid.isBlank()) return
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE messages SET is_deleted = 1, text_body = '', reactions_summary = '' WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
                arrayOf<Any>(accountId, chatJid, targetMessageId)
            )
            db.execSQL(
                "UPDATE chats SET last_message_preview = 'This message was deleted' WHERE account_id = ? AND chat_jid = ? AND last_message_id = ?",
                arrayOf<Any>(accountId, chatJid, targetMessageId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    fun applyMessageEdit(accountId: Int, chatJidRaw: String, targetMessageId: String, newText: String) {
        requireValidAccount(accountId)
        val chatJid = findOwningChatJidByMessageId(accountId, chatJidRaw, targetMessageId)
        if (chatJid.isBlank()) return
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE messages SET text_body = ?, is_edited = 1 WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
                arrayOf<Any>(newText, accountId, chatJid, targetMessageId)
            )
            db.execSQL(
                "UPDATE chats SET last_message_preview = ? WHERE account_id = ? AND chat_jid = ? AND last_message_id = ?",
                arrayOf<Any>(newText, accountId, chatJid, targetMessageId)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bumpConversation(accountId, chatJid, alsoInbox = true)
    }

    fun updateMessageReceiptStatus(accountId: Int, chatJidRaw: String, messageIds: List<String>, newStatus: String) {
        requireValidAccount(accountId)
        if (messageIds.isEmpty()) return
        val touchedChats = LinkedHashSet<String>()
        db.beginTransaction()
        try {
            for (msgId in messageIds) {
                val owningChat = findOwningChatJidByMessageId(accountId, chatJidRaw, msgId)
                if (owningChat.isBlank()) continue
                touchedChats.add(owningChat)
                if (newStatus == "read") {
                    db.execSQL(
                        "UPDATE messages SET status = 'read' WHERE account_id = ? AND chat_jid = ? AND message_id = ?",
                        arrayOf<Any>(accountId, owningChat, msgId)
                    )
                } else if (newStatus == "delivered") {
                    db.execSQL(
                        "UPDATE messages SET status = 'delivered' WHERE account_id = ? AND chat_jid = ? AND message_id = ? AND status != 'read'",
                        arrayOf<Any>(accountId, owningChat, msgId)
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        for (chat in touchedChats) {
            bumpConversation(accountId, chat, alsoInbox = false)
        }
    }

    fun updateMediaDownloadState(
        accountId: Int,
        chatJidRaw: String,
        messageId: String,
        localPath: String,
        downloadState: String,
        errorMessage: String = ""
    ) {
        requireValidAccount(accountId)
        val chatJid = findOwningChatJidByMessageId(accountId, chatJidRaw, messageId)
        if (chatJid.isBlank()) return
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
        bumpConversation(accountId, chatJid, alsoInbox = false)
    }

    fun updateChatTyping(accountId: Int, chatJidRaw: String, isTyping: Boolean, senderJid: String) {
        requireValidAccount(accountId)
        val chatJid = resolveCanonicalChatJid(accountId, chatJidRaw)
        db.execSQL(
            "UPDATE chats SET is_typing = ?, typing_sender = ? WHERE account_id = ? AND chat_jid = ?",
            arrayOf<Any>(if (isTyping) 1 else 0, senderJid, accountId, chatJid)
        )
        bumpInbox()
    }

    // =========================================================================
    // GROUPS & SYNCHRONIZATION STATE
    // =========================================================================

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
        bumpContactsGroups()
        bumpInbox()
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
                out.add(
                    GroupRecord(
                        accountId = c.getInt(0),
                        groupJid = c.getString(1),
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
        bumpAccounts()
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

    // =========================================================================
    // APP SETTINGS & PORTABLE CROSS-INSTALL MIGRATION SNAPSHOT
    // =========================================================================

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("setting_key", key)
            put("setting_value", value)
            put("updated_at_ms", System.currentTimeMillis())
        }
        db.insert("app_settings", SQLiteDatabase.CONFLICT_REPLACE, cv)
        bumpSettings()
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

    fun getAllSettings(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        db.query("SELECT setting_key, setting_value FROM app_settings").use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getString(1)
            }
        }
        return map
    }

    fun exportPortableSnapshot(): PortableBackupSnapshot {
        val allGroups = getGroups().map { g ->
            g.copy(members = getGroupMembers(g.accountId, g.groupJid))
        }
        val allChats = getUnifiedInboxChats(includeArchived = true)
        val allMessages = queryMessagesInternal(
            whereClause = "",
            args = emptyArray(),
            limit = 50_000
        )
        return PortableBackupSnapshot(
            formatVersion = 2,
            appVersion = "1.1.0",
            exportedAtMs = System.currentTimeMillis(),
            accounts = getAccounts(),
            identityMappings = getIdentityMappings(),
            contacts = getContacts(),
            groups = allGroups,
            chats = allChats,
            messages = allMessages,
            settings = getAllSettings()
        )
    }

    data class SnapshotImportSummary(
        val importedChats: Int,
        val importedMessages: Int,
        val importedContacts: Int
    )

    /**
     * Non-destructively merges a PortableBackupSnapshot into the live SQLCipher database
     * inside a single atomic transaction without deleting any existing sessions or messages.
     */
    fun importPortableSnapshotNonDestructive(snapshot: PortableBackupSnapshot): SnapshotImportSummary {
        var chatCount = 0
        var msgCount = 0
        var contactCount = 0

        db.beginTransaction()
        try {
            // 1. Account slot labels (never overwrite active live session state)
            for (acct in snapshot.accounts) {
                if (acct.accountId in 1..4 && acct.label.isNotBlank()) {
                    db.execSQL(
                        "UPDATE accounts SET label = ? WHERE account_id = ?",
                        arrayOf<Any>(acct.label, acct.accountId)
                    )
                }
            }
            // 2. Identity mappings
            for (m in snapshot.identityMappings) {
                if (m.accountId in 1..4) {
                    recordIdentityMappingAndMerge(m.accountId, m.lidJid, m.pnJid, m.mappingSource)
                }
            }
            // 3. Contacts & Groups per account
            for (acctId in 1..4) {
                val acctContacts = snapshot.contacts.filter { it.accountId == acctId }
                if (acctContacts.isNotEmpty()) {
                    upsertContacts(acctId, acctContacts)
                    contactCount += acctContacts.size
                }
                val acctGroups = snapshot.groups.filter { it.accountId == acctId }
                if (acctGroups.isNotEmpty()) {
                    upsertGroups(acctId, acctGroups)
                }
            }
            // 4. Chats
            for (chat in snapshot.chats) {
                if (chat.accountId !in 1..4 || chat.chatJid.isBlank()) continue
                val canon = ensureChatExists(chat.accountId, chat.chatJid, chat.title, chat.isGroup)
                if (chat.isPinned) setChatPinned(chat.accountId, canon, true)
                if (chat.isArchived) setChatArchived(chat.accountId, canon, true)
                if (chat.isMuted) setChatMuted(chat.accountId, canon, true)
                chatCount++
            }
            // 5. Messages (clear local file paths if missing on new device so user can re-download cleanly)
            for (msg in snapshot.messages) {
                if (msg.accountId !in 1..4 || msg.chatJid.isBlank() || msg.messageId.isBlank()) continue
                val sanitizedMedia = msg.media?.let { mm ->
                    val exists = mm.localPath.isNotBlank() && java.io.File(mm.localPath).exists()
                    if (exists) mm else mm.copy(localPath = "", downloadState = "none")
                }
                val inserted = upsertMessageAndUpdateChat(
                    record = msg.copy(media = sanitizedMedia),
                    incrementUnread = false
                )
                if (inserted) msgCount++
            }
            // 6. Settings
            for ((k, v) in snapshot.settings) {
                setSetting(k, v)
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        bumpAccounts()
        bumpInbox()
        bumpContactsGroups()
        bumpSettings()
        return SnapshotImportSummary(chatCount, msgCount, contactCount)
    }

    fun clearLocalMediaReferences() {
        db.execSQL("UPDATE message_media SET local_path = '', download_state = 'none'")
        bumpInbox()
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
        const val DB_VERSION = 2

        fun createEncrypted(context: Context, keyManager: KeystoreKeyManager): WhatsAppHubDatabase {
            System.loadLibrary("sqlcipher")
            runCatching {
                val loggerCls = Class.forName("net.zetetic.database.Logger")
                val targetIface = Class.forName("net.zetetic.database.LogTarget")
                val noopCls = Class.forName("net.zetetic.database.NoopTarget")
                loggerCls.getMethod("setTarget", targetIface)
                    .invoke(null, noopCls.getDeclaredConstructor().newInstance())
            }
            val keyBytes = keyManager.getOrCreateDatabaseKey()
            val factory = SupportOpenHelperFactory(keyBytes)
            return WhatsAppHubDatabase(context.applicationContext, DB_FILE_NAME, factory)
        }
    }
}
