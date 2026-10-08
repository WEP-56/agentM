package dev.agentm.app

import org.json.JSONArray
import org.json.JSONObject

class AppLogs {
    private val entries = ArrayDeque<JSONObject>()
    private var sequence = 0L

    @Synchronized fun add(tag: String, message: String, level: String = "I") {
        entries.addLast(JSONObject().put("id", ++sequence).put("t", System.currentTimeMillis())
            .put("tag", tag).put("msg", message.take(2048)).put("level", level))
        while (entries.size > 200) entries.removeFirst()
    }
    @Synchronized fun snapshot(): JSONArray = JSONArray(entries.toList())
    @Synchronized fun clear() = entries.clear()
}
