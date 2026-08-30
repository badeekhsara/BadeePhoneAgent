package com.badee.phoneagent.protocol

import org.json.JSONObject
import java.util.UUID

data class AgentCommand(
    val id: String,
    val token: String,
    val action: String,
    val args: JSONObject,
) {
    companion object {
        fun parse(raw: String): AgentCommand {
            val json = JSONObject(raw)
            val action = json.optString("action").trim().lowercase()
            require(action.matches(Regex("[a-z_]{2,40}"))) { "Invalid action" }

            return AgentCommand(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                token = json.optString("token"),
                action = action,
                args = json.optJSONObject("args") ?: JSONObject(),
            )
        }
    }
}

data class AgentResult(
    val id: String,
    val ok: Boolean,
    val message: String,
    val data: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("ok", ok)
        .put("message", message)
        .apply { if (data != null) put("data", data) }

    companion object {
        fun failure(id: String, message: String) = AgentResult(id, false, message)
        fun success(id: String, message: String, data: JSONObject? = null) =
            AgentResult(id, true, message, data)
    }
}
