package com.scanner.app.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.PointF
import com.scanner.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

internal class PageStore(context: Context) : SQLiteOpenHelper(context, "scans.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pages (id TEXT PRIMARY KEY, position INTEGER NOT NULL, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun load(): List<ScannedPage> = readableDatabase.query(
        "pages", arrayOf("payload"), null, null, null, null, "position ASC"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(decode(JSONObject(cursor.getString(0)))) } }
    fun save(pages: List<ScannedPage>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("pages", null, null)
            pages.forEachIndexed { index, page ->
                db.insertOrThrow("pages", null, ContentValues().apply {
                    put("id", page.id); put("position", index); put("payload", encode(page).toString())
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun points(points: List<PointF>) = JSONArray(points.flatMap { listOf(it.x, it.y) })
    private fun decodePoints(array: JSONArray) = (0 until array.length() step 2).map {
        PointF(array.getDouble(it).toFloat(), array.getDouble(it + 1).toFloat())
    }
    private fun encode(page: ScannedPage) = JSONObject().apply {
        put("id", page.id); put("original", page.originalImagePath)
        put("processed", page.processedImagePath ?: JSONObject.NULL)
        put("thumbnail", page.thumbnailPath ?: JSONObject.NULL)
        put("sourceRotation", page.sourceRotation); put("filter", page.filter.name); put("rotation", page.rotation)
        put("ratio", page.targetAspectRatio ?: JSONObject.NULL); put("created", page.createdAt)
        page.quad?.let { put("quad", points(it.toPointList())) }
        page.curvedBoundary?.let { b -> put("boundary", JSONObject().apply {
            put("top", points(b.topCurve)); put("bottom", points(b.bottomCurve))
            put("left", points(b.leftCurve)); put("right", points(b.rightCurve))
        }) }
    }
    private fun decode(json: JSONObject): ScannedPage {
        val quad = json.optJSONArray("quad")?.let { a ->
            val p = decodePoints(a); DocumentQuad(p[0], p[1], p[2], p[3])
        }
        val boundary = json.optJSONObject("boundary")?.let { b -> CurvedBoundary(
            decodePoints(b.getJSONArray("top")), decodePoints(b.getJSONArray("bottom")),
            decodePoints(b.getJSONArray("left")), decodePoints(b.getJSONArray("right"))
        ) }
        fun optional(key: String) = if (json.isNull(key)) null else json.getString(key)
        return ScannedPage(
            id = json.getString("id"), originalImagePath = json.getString("original"),
            processedImagePath = optional("processed"), thumbnailPath = optional("thumbnail"),
            quad = quad, curvedBoundary = boundary, filter = ImageFilter.valueOf(json.getString("filter")),
            sourceRotation = json.optInt("sourceRotation", 0), rotation = json.getInt("rotation"),
            targetAspectRatio = if (json.isNull("ratio")) null else json.getDouble("ratio").toFloat(),
            createdAt = json.getLong("created")
        )
    }
}
