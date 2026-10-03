package com.soros.v2.entity

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * List<String> ↔ TEXT 列的 JSON 序列化转换器（stock_info.industry / stock_info.concept_boards）。
 *
 * schema.sql 中 industry / concept_boards 为 TEXT 列（非 JSONB），Hibernate ddl-auto=validate
 * 将 @JdbcTypeCode(SqlTypes.JSON) 期望为 jsonb 而报列类型冲突；改用 JPA AttributeConverter
 * 把 List<String> 以 JSON 文本写入 TEXT 列，列类型映射到 VARCHAR 与 TEXT 一致，validate 通过。
 */
@Converter
class StringListJsonConverter : AttributeConverter<List<String>, String> {

    private val objectMapper = ObjectMapper()
    private val listType = object : TypeReference<List<String>>() {}

    override fun convertToDatabaseColumn(attribute: List<String>?): String? =
        attribute?.let { objectMapper.writeValueAsString(it) }

    override fun convertToEntityAttribute(dbData: String?): List<String>? =
        dbData?.let { objectMapper.readValue(it, listType) }
}
