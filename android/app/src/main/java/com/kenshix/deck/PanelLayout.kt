package com.kenshix.deck

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class Panel(val id: String, val name: String, val parent: String?, val kind: String)

internal class PanelLayout(source: JSONObject?) {
    private val json = JSONObject(source?.toString() ?: "{}")
    private val panels = json.optJSONArray("panels") ?: JSONArray().also { json.put("panels", it) }
    private val placements = json.optJSONObject("placements") ?: JSONObject().also { json.put("placements", it) }

    fun snapshot(): JSONObject = json

    fun items(): List<Panel> = (0 until panels.length()).map { index ->
        val node = panels.getJSONObject(index)
        Panel(node.getString("id"), node.getString("name"), node.stringOrNull("parent"), node.getString("kind"))
    }

    fun item(id: String?): Panel? = items().firstOrNull { it.id == id }

    fun add(name: String, parent: String?, kind: String): String {
        val id = UUID.randomUUID().toString()
        panels.put(JSONObject().put("id", id).put("name", name).put("parent", parent ?: JSONObject.NULL).put("kind", kind))
        return id
    }

    fun rename(id: String, name: String) {
        for (index in 0 until panels.length()) {
            val node = panels.getJSONObject(index)
            if (node.getString("id") == id) node.put("name", name)
        }
    }

    fun empty(id: String): Boolean =
        items().none { it.parent == id } && placements.keys().asSequence().none { panelFor(it) == id }

    fun remove(id: String) {
        for (index in 0 until panels.length()) {
            if (panels.getJSONObject(index).getString("id") == id) {
                panels.remove(index)
                return
            }
        }
    }

    fun panelFor(button: String): String? = placements.optJSONObject(button)?.stringOrNull("panel")
    fun spanFor(button: String): Int = placements.optJSONObject(button)?.optInt("span", 1) ?: 1
    fun colorFor(button: String): String? = placements.optJSONObject(button)?.stringOrNull("color")

    fun place(button: String, panel: String?, span: Int, tint: String?) {
        if (panel == null && span == 1 && tint == null) {
            placements.remove(button)
        } else {
            placements.put(button, JSONObject().put("panel", panel ?: JSONObject.NULL)
                .put("span", span).put("color", tint ?: JSONObject.NULL))
        }
    }

    fun unplace(button: String) {
        placements.remove(button)
    }
}

private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)
