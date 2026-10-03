package com.soros.v2.domain

/**
 * 板块类型（§4.8 板块归属采集频率：行业每日全量 / 概念每周全量）。
 *
 * pythonName 与 Python /api/v1/board-members 的 board_type 字面量对齐
 * （BOARD_TYPE_INDUSTRY="industry" / BOARD_TYPE_CONCEPT="concept"）。
 */
enum class BoardType(val pythonName: String) {
    INDUSTRY("industry"),
    CONCEPT("concept"),
}
