package com.kama.mindagent.service;

import com.kama.mindagent.service.impl.MarkdownParserServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownParserServiceImplTest {

    private final MarkdownParserService parser = new MarkdownParserServiceImpl();

    @Test
    void parseMarkdown_extractsThreeTopLevelSections() {
        String markdown = """
                # BASELINE_A

                first section content

                ## BASELINE_B

                second section content

                # BASELINE_C

                third section content
                """;

        List<MarkdownParserService.MarkdownSection> sections = parse(markdown);

        assertThat(sections).extracting(MarkdownParserService.MarkdownSection::getTitle)
                .containsExactly("BASELINE_A", "BASELINE_B", "BASELINE_C");
        assertThat(sections).extracting(MarkdownParserService.MarkdownSection::getContent)
                .containsExactly(
                        "first section content",
                        "second section content",
                        "third section content"
                );
    }

    @Test
    void parseMarkdown_preservesTableMarkdownInsideSection() {
        String markdown = """
                # TABLE_SECTION

                | key | value |
                | --- | --- |
                | one | 1 |
                """;

        List<MarkdownParserService.MarkdownSection> sections = parse(markdown);

        assertThat(sections).singleElement()
                .extracting(MarkdownParserService.MarkdownSection::getContent)
                .asString()
                .contains("| key | value |", "| one | 1 |");
    }

    @Test
    void parseMarkdown_withoutHeadings_returnsOneUntitledSection() {
        List<MarkdownParserService.MarkdownSection> sections =
                parse("plain text without a heading");

        assertThat(sections).singleElement().satisfies(section -> {
            assertThat(section.getTitle()).isNull();
            assertThat(section.getContent()).isEqualTo("plain text without a heading");
        });
    }

    @Test
    void parseMarkdown_withOnlyWhitespace_returnsNoSections() {
        assertThat(parse("  \n\t  ")).isEmpty();
    }

    private List<MarkdownParserService.MarkdownSection> parse(String markdown) {
        return parser.parseMarkdown(new ByteArrayInputStream(markdown.getBytes(StandardCharsets.UTF_8)));
    }
}
