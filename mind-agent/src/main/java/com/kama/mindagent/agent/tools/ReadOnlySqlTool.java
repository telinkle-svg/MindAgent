package com.kama.mindagent.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ReadOnlySqlTool implements AgentTool {

    private static final int MAX_ROWS = 100;
    private static final String POLICY_REJECTED = "错误：SQL 查询不符合只读访问策略。";
    private static final String EXECUTION_FAILED = "错误：查询执行失败，请检查查询条件后重试。";

    private final JdbcTemplate jdbcTemplate;
    private final ReadOnlySqlPolicy readOnlySqlPolicy;

    public ReadOnlySqlTool(JdbcTemplate jdbcTemplate, ReadOnlySqlPolicy readOnlySqlPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.readOnlySqlPolicy = readOnlySqlPolicy;
    }

    @Override
    public String name() {
        return "dataBaseTool";
    }

    @Override
    public String description() {
        return "一个用于在 agent_tool.knowledge_base_summary、agent_tool.document_summary 和 "
                + "agent_tool.retrievable_content_summary 受控视图上执行只读查询的工具。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.OPTIONAL;
    }

    /**
     * 执行一条经过只读策略验证的 SQL 查询。
     *
     * @param sql 仅支持单条、只读 SELECT 或 WITH 查询，且只能访问受控视图
     * @return 格式化的查询结果字符串
     */
    @org.springframework.ai.tool.annotation.Tool(
            name = "databaseQuery",
            description = "在 agent_tool.knowledge_base_summary、agent_tool.document_summary 或 "
                    + "agent_tool.retrievable_content_summary 受控视图上执行单条只读 SELECT/WITH 查询。"
                    + "禁止访问其他表、写入、DDL、事务、锁定和多语句。"
    )
    public String executeQuery(String sql) {
        final String validatedSql;
        try {
            validatedSql = readOnlySqlPolicy.validate(sql);
        } catch (SqlPolicyViolationException exception) {
            log.warn("Database tool query rejected by read-only policy", exception);
            return POLICY_REJECTED;
        }

        try {
            List<String> rows = jdbcTemplate.query(validatedSql, (ResultSet rs) -> {
                List<String> resultRows = new ArrayList<>();
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                if (columnCount == 0) {
                    resultRows.add("查询结果为空（无列）");
                    return resultRows;
                }

                List<String> columnNames = new ArrayList<>();
                List<Integer> columnWidths = new ArrayList<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    columnNames.add(columnName);
                    columnWidths.add(columnName.length());
                }

                List<List<String>> dataRows = new ArrayList<>();
                while (rs.next() && dataRows.size() < MAX_ROWS) {
                    List<String> rowData = new ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        String valueStr = value == null ? "NULL" : value.toString();
                        rowData.add(valueStr);
                        int currentWidth = columnWidths.get(i - 1);
                        if (valueStr.length() > currentWidth) {
                            columnWidths.set(i - 1, valueStr.length());
                        }
                    }
                    dataRows.add(rowData);
                }

                StringBuilder header = new StringBuilder("| ");
                for (int i = 0; i < columnCount; i++) {
                    String columnName = columnNames.get(i);
                    int width = columnWidths.get(i);
                    header.append(String.format("%-" + width + "s", columnName)).append(" | ");
                }
                resultRows.add(header.toString());

                StringBuilder separator = new StringBuilder("|");
                for (int i = 0; i < columnCount; i++) {
                    int width = columnWidths.get(i);
                    separator.append("-".repeat(width + 2)).append("|");
                }
                resultRows.add(separator.toString());

                if (dataRows.isEmpty()) {
                    StringBuilder emptyRow = new StringBuilder("| ");
                    int totalWidth = columnWidths.stream().mapToInt(width -> width + 3).sum() - 1;
                    emptyRow.append(String.format("%-" + (totalWidth - 2) + "s", "(无数据)"));
                    emptyRow.append(" |");
                    resultRows.add(emptyRow.toString());
                } else {
                    for (List<String> rowData : dataRows) {
                        StringBuilder row = new StringBuilder("| ");
                        for (int i = 0; i < columnCount; i++) {
                            String value = rowData.get(i);
                            int width = columnWidths.get(i);
                            row.append(String.format("%-" + width + "s", value)).append(" | ");
                        }
                        resultRows.add(row.toString());
                    }
                }

                return resultRows;
            });

            int dataRowCount = Math.max(0, rows.size() - 2);
            if (rows.size() > 2 && rows.get(rows.size() - 1).contains("(无数据)")) {
                dataRowCount = 0;
            }
            log.info("Database tool query succeeded, returned {} rows", dataRowCount);
            return "查询结果:\n" + String.join("\n", rows);
        } catch (Exception exception) {
            log.warn("Database tool query execution failed", exception);
            return EXECUTION_FAILED;
        }
    }
}
