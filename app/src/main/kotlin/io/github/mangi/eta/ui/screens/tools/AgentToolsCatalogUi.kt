package io.github.mangi.eta.ui.screens.tools

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.ToolItemUi

/** Always derive switch coverage from registered schemas, including future file/web tools. */
internal object AgentToolsCatalogUi {
    fun complete(groups: List<ToolGroupUi>): List<ToolGroupUi> {
        val schemas = AgentToolCatalog.allForSwitches()
        val registered = linkedMapOf<String, ToolItemUi>()
        for (index in 0 until schemas.length()) {
            val function = schemas.getJSONObject(index).getJSONObject("function")
            val name = function.getString("name")
            val id = Prefs.canonicalToolSwitchId(name)
            registered.putIfAbsent(id, ToolItemUi(name, name, function.optString("description")))
        }
        val seen = mutableSetOf<String>()
        val result = groups.mapNotNull { group ->
            val tools = group.tools.mapNotNull { tool ->
                val id = Prefs.canonicalToolSwitchId(tool.id)
                if (id in registered && seen.add(id)) tool else null
            }
            if (tools.isEmpty()) null else group.copy(tools = tools)
        }.toMutableList()
        val extra = registered.filterKeys { it !in seen }.values.toList()
        val fileNames = io.github.mangi.eta.agent.model.AgentFileToolCatalog.names
        val partitions = extra.groupBy { when (it.id) {
            in fileNames -> "terminal"
            "web_search", "fetch_url" -> "web"
            "generate_image" -> "file_vision"
            else -> "registered"
        } }
        partitions.forEach { (id, tools) ->
            val index = result.indexOfFirst { it.id == id }
            if (index >= 0) result[index] = result[index].copy(tools = result[index].tools + tools)
            else result += ToolGroupUi(id, when (id) {
                "terminal" -> "终端与文件"
                "web" -> "网页浏览"
                "file_vision" -> "文件视觉"
                else -> "更多本机工具"
            }, tools)
        }
        return result
    }
}
