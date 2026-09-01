package com.kama.mindagent.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMessageMapperAnchorSqlTest {

    @Test
    void anchoredRecentQueryFiltersAtOrBeforeAnchorInsideNewestFirstSubquery() throws IOException {
        String mapperXml;
        try (InputStream input = getClass().getResourceAsStream("/mapper/ChatMessageMapper.xml")) {
            assertThat(input).isNotNull();
            mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int queryStart = mapperXml.indexOf("<select id=\"selectBySessionIdRecentlyBefore\"");
        int queryEnd = mapperXml.indexOf("</select>", queryStart);
        String query = mapperXml.substring(queryStart, queryEnd).replaceAll("\\s+", " ");

        assertThat(query).contains("created_at &lt; #{anchorCreatedAt}");
        assertThat(query).contains("created_at = #{anchorCreatedAt} AND id &lt;= CAST(#{anchorMessageId} AS uuid)");
        int newestFirstOrder = query.indexOf("ORDER BY created_at DESC, id DESC");
        int limit = query.indexOf("LIMIT #{limit}");
        int chronologicalOrder = query.lastIndexOf("ORDER BY created_at ASC, id ASC");
        assertThat(newestFirstOrder).isGreaterThanOrEqualTo(0);
        assertThat(limit).isGreaterThan(newestFirstOrder);
        assertThat(chronologicalOrder).isGreaterThan(limit);
    }
}
