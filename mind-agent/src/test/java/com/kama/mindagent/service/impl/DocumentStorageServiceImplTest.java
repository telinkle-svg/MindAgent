package com.kama.mindagent.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentStorageServiceImplTest {

    @TempDir
    Path temporaryDirectory;

    private DocumentStorageServiceImpl storageService;

    @BeforeEach
    void setUp() {
        storageService = new DocumentStorageServiceImpl();
        ReflectionTestUtils.setField(storageService, "baseStoragePath", temporaryDirectory.toString());
    }

    @Test
    void saveFileWhenCopyFails_deletesPartialFileAndEmptyDocumentDirectory() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("broken.md");
        when(file.getInputStream()).thenReturn(new FailingInputStream());

        assertThatThrownBy(() -> storageService.saveFile("kb-1", "doc-1", file))
                .isInstanceOf(IOException.class)
                .hasMessage("simulated copy failure");

        assertThat(Files.exists(temporaryDirectory.resolve("kb-1").resolve("doc-1"))).isFalse();
    }

    private static final class FailingInputStream extends InputStream {
        private boolean firstRead = true;

        @Override
        public int read() throws IOException {
            if (firstRead) {
                firstRead = false;
                return 'x';
            }
            throw new IOException("simulated copy failure");
        }
    }
}
