package com.soros.v2.service.strategy

import com.soros.v2.service.strategy.dto.ConditionInput

/**
 * §12.9 决策 1：结构化条件树 → YAML 服务端拼装（无自由 YAML 文本，落库即权威）。
 *
 * 生成口径：`strategy: <name>` + `signals:` 下按 side 分组，每条件一行列表项
 * （cond_id/source/op/value）。value 列表渲染为 `[a, b]` 区间。
 */
object YamlGenerator {

    fun generate(name: String, conditions: List<ConditionInput>): String {
        val sb = StringBuilder()
        sb.append("strategy: ").append(name).append('\n')
        sb.append("signals:\n")
        conditions.groupBy { it.side }.forEach { (side, conds) ->
            sb.append("  ").append(side).append(":\n")
            conds.forEach { c ->
                sb.append("    - cond_id: ").append(c.condId).append('\n')
                sb.append("      source: ").append(c.source).append('\n')
                sb.append("      op: ").append(c.op).append('\n')
                if (c.value != null) {
                    sb.append("      value: ").append(formatValue(c.value)).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun formatValue(value: Any): String = when (value) {
        is List<*> -> "[${value.joinToString(", ") { it?.toString() ?: "" }}]"
        is String -> "\"$value\""
        is Number, is Boolean -> value.toString()
        else -> value.toString()
    }
}
