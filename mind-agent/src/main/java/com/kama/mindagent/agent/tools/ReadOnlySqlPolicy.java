package com.kama.mindagent.agent.tools;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ReadOnlySqlPolicy {

    private static final Set<String> CONTROLLED_RELATIONS = Set.of(
            "agent_tool.knowledge_base_summary",
            "agent_tool.document_summary",
            "agent_tool.retrievable_content_summary"
    );

    private static final Pattern LOCKING_CLAUSE = Pattern.compile(
            "(?is)\\bFOR\\s+(?:NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE|UPDATE|SHARE)\\b"
                    + "|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b"
    );

    private static final Pattern DATA_MODIFYING_CTE = Pattern.compile(
            "(?is)^\\s*WITH\\b.*\\b(?:INSERT|UPDATE|DELETE|MERGE)\\b"
    );

    private static final Pattern SELECT_INTO = Pattern.compile(
            "(?is)\\bINTO\\b"
    );

    public String validate(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new SqlPolicyViolationException("SQL must not be blank");
        }

        String trimmedSql = sql.trim();
        if (LOCKING_CLAUSE.matcher(trimmedSql).find()
                || DATA_MODIFYING_CTE.matcher(trimmedSql).find()) {
            throw new SqlPolicyViolationException("SQL contains a prohibited operation");
        }

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(trimmedSql);
            if (statements.getStatements().size() != 1) {
                throw new SqlPolicyViolationException("Exactly one SQL statement is required");
            }

            Statement statement = statements.getStatements().get(0);
            if (!(statement instanceof Select select)) {
                throw new SqlPolicyViolationException("Only SELECT statements are allowed");
            }
            if (select.getForClause() != null) {
                throw new SqlPolicyViolationException("Locking clauses are not allowed");
            }
            if (select instanceof PlainSelect plainSelect
                    && plainSelect.getIntoTables() != null
                    && !plainSelect.getIntoTables().isEmpty()) {
                throw new SqlPolicyViolationException("SELECT INTO is not allowed");
            }
            if (SELECT_INTO.matcher(trimmedSql).find()) {
                throw new SqlPolicyViolationException("SELECT INTO is not allowed");
            }

            Set<String> relations = new TablesNamesFinder().getTables(statement);
            if (relations.isEmpty() || relations.stream()
                    .map(ReadOnlySqlPolicy::normalizeRelation)
                    .anyMatch(relation -> !CONTROLLED_RELATIONS.contains(relation))) {
                throw new SqlPolicyViolationException("Only controlled agent_tool views are allowed");
            }

            return trimmedSql;
        } catch (SqlPolicyViolationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SqlPolicyViolationException("SQL parsing failed", exception);
        }
    }

    private static String normalizeRelation(String relation) {
        return relation
                .replace("\"", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
