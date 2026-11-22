package com.craft.capturespace.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Days a deleted capture stays in Trash (the original shows the same 30-day rule). */
const val TRASH_DAYS = 30

private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "space.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE cards(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                created INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                file TEXT, mime TEXT, source_app TEXT,
                status TEXT NOT NULL DEFAULT 'pending',
                summary TEXT NOT NULL DEFAULT '',
                tags TEXT NOT NULL DEFAULT '[]',
                details TEXT NOT NULL DEFAULT '[]',
                transcript TEXT NOT NULL DEFAULT '',
                topics TEXT NOT NULL DEFAULT '[]',
                actions TEXT NOT NULL DEFAULT '[]',
                key_moments TEXT NOT NULL DEFAULT '[]',
                marks TEXT NOT NULL DEFAULT '[]',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                error TEXT,
                deleted_at INTEGER,
                embedding BLOB,
                suggestion TEXT,
                audio TEXT,
                audio_ms INTEGER NOT NULL DEFAULT 0,
                user_title INTEGER NOT NULL DEFAULT 0,
                meta TEXT)"""
        )
        db.execSQL("CREATE TABLE collections(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE card_collections(card_id INTEGER NOT NULL, collection_id INTEGER NOT NULL, PRIMARY KEY(card_id, collection_id))")
        db.execSQL(
            """CREATE TABLE items(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                card_id INTEGER,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                start INTEGER, end_time INTEGER,
                all_day INTEGER NOT NULL DEFAULT 0,
                location TEXT NOT NULL DEFAULT '',
                done INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL,
                suggested INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    // Version 2: tasks and events the AI finds are stored as suggestions (suggested = 1) and only
    // become real items, with reminders, when the person taps to add them.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE items ADD COLUMN suggested INTEGER NOT NULL DEFAULT 0")
        // Version 3: a voice note held down on the photo/screenshot screen is kept with the picture.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE cards ADD COLUMN audio TEXT")
            db.execSQL("ALTER TABLE cards ADD COLUMN audio_ms INTEGER NOT NULL DEFAULT 0")
        }
        // Version 4: a title the person typed is kept across analyses; a shared link's public
        // preview or a document's type and page count (JSON, see Meta).
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE cards ADD COLUMN user_title INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE cards ADD COLUMN meta TEXT")
        }
    }
}

class Repo(private val ctx: Context) {
    private val db: SQLiteDatabase = Db(ctx).writableDatabase

    /** Bumped on every change so screens reload. */
    val changes = MutableStateFlow(0)
    private fun bump() {
        synchronized(changes) { changes.value = changes.value + 1 }
    }

    fun capturesDir(): File = File(ctx.filesDir, "captures").apply { mkdirs() }
    fun newFile(ext: String): File =
        File(capturesDir(), "${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")

    // ---------- cards ----------

    fun insertCard(
        type: String,
        note: String = "",
        file: String? = null,
        mime: String? = null,
        sourceApp: String? = null,
        durationMs: Long = 0,
        marks: List<Long> = emptyList(),
        status: String = Status.PENDING,
        audio: String? = null,
        audioMs: Long = 0,
        meta: String? = null,
    ): Long {
        val v = ContentValues().apply {
            put("type", type)
            put("meta", meta)
            put("created", System.currentTimeMillis())
            put("note", note)
            put("file", file)
            put("mime", mime)
            put("source_app", sourceApp)
            put("duration_ms", durationMs)
            put("marks", Json.longs(marks))
            put("status", status)
            put("audio", audio)
            put("audio_ms", audioMs)
        }
        val id = db.insert("cards", null, v)
        bump()
        return id
    }

    fun card(id: Long): Card? =
        db.rawQuery("SELECT * FROM cards WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toCard() else null
        }

    fun cards(type: String? = null): List<Card> {
        val sql = if (type == null) "SELECT * FROM cards WHERE deleted_at IS NULL ORDER BY created DESC"
        else "SELECT * FROM cards WHERE deleted_at IS NULL AND type=? ORDER BY created DESC"
        val args = if (type == null) null else arrayOf(type)
        return db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toCard()) } }
    }

    fun cardsWithStatus(vararg statuses: String): List<Card> {
        val marks = statuses.joinToString(",") { "?" }
        return db.rawQuery(
            "SELECT * FROM cards WHERE deleted_at IS NULL AND status IN ($marks) ORDER BY created",
            arrayOf(*statuses)
        ).use { c -> buildList { while (c.moveToNext()) add(c.toCard()) } }
    }

    fun trash(): List<Card> =
        db.rawQuery("SELECT * FROM cards WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toCard()) } }

    fun update(id: Long, v: ContentValues) {
        db.update("cards", v, "id=?", arrayOf(id.toString()))
        bump()
    }

    fun setStatus(id: Long, status: String, error: String? = null) =
        update(id, ContentValues().apply { put("status", status); put("error", error) })

    fun setNote(id: Long, note: String) = update(id, ContentValues().apply { put("note", note) })
    /** A title typed by the person: kept across analyses (an empty one hands the title back to the analysis). */
    fun setTitle(id: Long, title: String) = update(id, ContentValues().apply {
        put("title", title.trim()); put("user_title", if (title.isBlank()) 0 else 1)
    })

    /** Facts found by code, as JSON: a document's type and pages, a link's public preview (see [Meta]). */
    fun setMeta(id: Long, json: String?) = update(id, ContentValues().apply { put("meta", json) })
    fun setSuggestion(id: Long, s: Suggestion?) =
        update(id, ContentValues().apply { put("suggestion", Json.suggestion(s)) })

    fun setEmbedding(id: Long, e: FloatArray?) =
        update(id, ContentValues().apply { put("embedding", e?.let { floatsToBytes(it) }) })

    /** Drops every search index (after the search model changes) so they are rebuilt. */
    fun clearEmbeddings() {
        db.execSQL("UPDATE cards SET embedding = NULL")
        bump()
    }

    fun moveToTrash(id: Long) {
        update(id, ContentValues().apply { put("deleted_at", System.currentTimeMillis()) })
        db.delete("items", "card_id=?", arrayOf(id.toString()))
        bump()
    }

    fun restore(id: Long) = update(id, ContentValues().apply { putNull("deleted_at") })

    fun deleteForever(id: Long) {
        card(id)?.let { c ->
            c.file?.let { File(it).delete() }; c.audio?.let { File(it).delete() }
            // Pictures made for the card: a document's cover, a link's preview picture.
            listOfNotNull(Meta.doc(c.meta)?.cover, Meta.link(c.meta)?.image).forEach { File(it).delete() }
        }
        db.delete("cards", "id=?", arrayOf(id.toString()))
        db.delete("card_collections", "card_id=?", arrayOf(id.toString()))
        db.delete("items", "card_id=?", arrayOf(id.toString()))
        bump()
    }

    fun purgeTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 24L * 3600_000L
        trash().filter { (it.deletedAt ?: 0) < cutoff }.forEach { deleteForever(it.id) }
    }

    fun embeddings(): List<Pair<Long, FloatArray>> =
        db.rawQuery("SELECT id, embedding FROM cards WHERE deleted_at IS NULL AND embedding IS NOT NULL", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(c.getLong(0) to bytesToFloats(c.getBlob(1)))
                }
            }

    // ---------- collections ----------

    fun collections(): List<CollectionInfo> = db.rawQuery(
        """SELECT co.id, co.name, co.created,
               (SELECT COUNT(*) FROM card_collections cc JOIN cards ca ON ca.id=cc.card_id
                 WHERE cc.collection_id=co.id AND ca.deleted_at IS NULL),
               (SELECT ca.file FROM card_collections cc JOIN cards ca ON ca.id=cc.card_id
                 WHERE cc.collection_id=co.id AND ca.deleted_at IS NULL AND ca.type='image'
                 ORDER BY ca.created DESC LIMIT 1)
           FROM collections co ORDER BY co.created DESC""", null
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                CollectionInfo(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3), c.getStringOrNull(4))
            )
        }
    }

    fun collection(id: Long): CollectionInfo? = collections().firstOrNull { it.id == id }

    /** Returns the new id, or -1 when a collection with that name already exists. */
    fun createCollection(name: String): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1
        val exists = db.rawQuery("SELECT id FROM collections WHERE name=? COLLATE NOCASE", arrayOf(clean))
            .use { it.moveToFirst() }
        if (exists) return -1
        val id = db.insert("collections", null, ContentValues().apply {
            put("name", clean); put("created", System.currentTimeMillis())
        })
        bump()
        return id
    }

    fun renameCollection(id: Long, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val clash = db.rawQuery(
            "SELECT id FROM collections WHERE name=? COLLATE NOCASE AND id<>?", arrayOf(clean, id.toString())
        ).use { it.moveToFirst() }
        if (clash) return false
        db.update("collections", ContentValues().apply { put("name", clean) }, "id=?", arrayOf(id.toString()))
        bump()
        return true
    }

    fun deleteCollection(id: Long) {
        db.delete("collections", "id=?", arrayOf(id.toString()))
        db.delete("card_collections", "collection_id=?", arrayOf(id.toString()))
        bump()
    }

    fun addToCollection(cardId: Long, collectionId: Long) {
        db.insertWithOnConflict("card_collections", null, ContentValues().apply {
            put("card_id", cardId); put("collection_id", collectionId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        bump()
    }

    fun removeFromCollection(cardId: Long, collectionId: Long) {
        db.delete("card_collections", "card_id=? AND collection_id=?", arrayOf(cardId.toString(), collectionId.toString()))
        bump()
    }

    fun collectionCards(collectionId: Long): List<Card> = db.rawQuery(
        """SELECT ca.* FROM cards ca JOIN card_collections cc ON cc.card_id=ca.id
           WHERE cc.collection_id=? AND ca.deleted_at IS NULL ORDER BY ca.created DESC""",
        arrayOf(collectionId.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toCard()) } }

    fun cardCollectionIds(cardId: Long): List<Long> =
        db.rawQuery("SELECT collection_id FROM card_collections WHERE card_id=?", arrayOf(cardId.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    // ---------- tasks and events ----------

    fun addItem(
        kind: String, title: String, start: Long?, end: Long?, allDay: Boolean,
        location: String = "", cardId: Long? = null, suggested: Boolean = false,
    ): Long {
        val id = db.insert("items", null, ContentValues().apply {
            put("card_id", cardId); put("kind", kind); put("title", title.trim())
            put("start", start); put("end_time", end); put("all_day", if (allDay) 1 else 0)
            put("location", location); put("created", System.currentTimeMillis())
            put("suggested", if (suggested) 1 else 0)
        })
        bump()
        return id
    }

    /** The person tapped to add a suggestion: it becomes a real task or event. */
    fun confirmItem(id: Long): PlanItem? {
        db.update("items", ContentValues().apply { put("suggested", 0) }, "id=?", arrayOf(id.toString()))
        bump()
        return item(id)
    }

    /** Suggestions found in captures that the person hasn't added or dismissed yet. */
    fun suggestedItems(): List<PlanItem> =
        db.rawQuery("SELECT * FROM items WHERE suggested=1 AND done=0 ORDER BY start IS NULL, start, created DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    fun updateItem(item: PlanItem) {
        db.update("items", ContentValues().apply {
            put("kind", item.kind); put("title", item.title); put("start", item.start)
            put("end_time", item.end); put("all_day", if (item.allDay) 1 else 0)
            put("location", item.location); put("done", if (item.done) 1 else 0)
        }, "id=?", arrayOf(item.id.toString()))
        bump()
    }

    fun deleteItem(id: Long) {
        db.delete("items", "id=?", arrayOf(id.toString()))
        bump()
    }

    fun item(id: Long): PlanItem? =
        db.rawQuery("SELECT * FROM items WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toItem() else null
        }

    fun items(kind: String? = null): List<PlanItem> {
        val sql = if (kind == null) "SELECT * FROM items WHERE suggested=0 ORDER BY done, start IS NULL, start, created DESC"
        else "SELECT * FROM items WHERE suggested=0 AND kind=? ORDER BY done, start IS NULL, start, created DESC"
        return db.rawQuery(sql, kind?.let { arrayOf(it) }).use { c ->
            buildList { while (c.moveToNext()) add(c.toItem()) }
        }
    }

    fun itemsForCard(cardId: Long): List<PlanItem> =
        db.rawQuery("SELECT * FROM items WHERE card_id=? ORDER BY start", arrayOf(cardId.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    fun deleteItemsForCard(cardId: Long) {
        db.delete("items", "card_id=?", arrayOf(cardId.toString()))
        bump()
    }

    // ---------- helpers ----------

    private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
    private fun Cursor.strOrNull(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.lng(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.lngOrNull(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.toCard(): Card {
        val embIdx = getColumnIndexOrThrow("embedding")
        return Card(
            id = lng("id"), type = str("type"), created = lng("created"), title = str("title"),
            note = str("note"), file = strOrNull("file"), mime = strOrNull("mime"),
            sourceApp = strOrNull("source_app"), status = str("status"), summary = str("summary"),
            tags = Json.parseStrings(str("tags")), details = Json.parseDetails(str("details")),
            transcript = str("transcript"), topics = Json.parseStrings(str("topics")),
            actions = Json.parseStrings(str("actions")), keyMoments = Json.parseStrings(str("key_moments")),
            marks = Json.parseLongs(str("marks")), durationMs = lng("duration_ms"),
            error = strOrNull("error"), deletedAt = lngOrNull("deleted_at"),
            embedding = if (isNull(embIdx)) null else bytesToFloats(getBlob(embIdx)),
            suggestion = Json.parseSuggestion(strOrNull("suggestion")),
            audio = strOrNull("audio"), audioMs = lng("audio_ms"),
            userTitle = lng("user_title") == 1L, meta = strOrNull("meta"),
        )
    }

    private fun Cursor.toItem() = PlanItem(
        id = lng("id"), cardId = lngOrNull("card_id"), kind = str("kind"), title = str("title"),
        start = lngOrNull("start"), end = lngOrNull("end_time"), allDay = lng("all_day") == 1L,
        location = str("location"), done = lng("done") == 1L, created = lng("created"),
        suggested = lng("suggested") == 1L,
    )

    companion object {
        fun floatsToBytes(f: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(f.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            f.forEach { b.putFloat(it) }
            return b.array()
        }

        fun bytesToFloats(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }
    }
}
