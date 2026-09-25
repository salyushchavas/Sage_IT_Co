package com.spire.backend.service;

import com.cloudinary.Cloudinary;
import com.spire.backend.entity.ParticipantDocument;
import com.spire.backend.entity.Role;
import com.spire.backend.entity.User;
import com.spire.backend.repository.ParticipantDocumentRepository;
import com.spire.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Production-readiness review (25 Sep): participant files go to S3 when it's
 * configured, are read back whatever storage they're in, are only real
 * PDFs/pictures, and a re-upload never loses the old file before the new one
 * is safely stored.
 */
class DocumentStorageTest {

    private static final byte[] PDF = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private DocumentStorageService storage(boolean s3On, DocumentStorage s3) {
        DocumentStorageService svc = new DocumentStorageService(mock(Cloudinary.class), s3);
        if (s3On) {
            ReflectionTestUtils.setField(svc, "s3Bucket", "sage-docs");
            ReflectionTestUtils.setField(svc, "s3Region", "us-east-1");
        }
        return svc;
    }

    @Test
    void withS3ConfiguredFilesGoToThePrivateBucketAndComeBack() {
        DocumentStorage s3 = mock(DocumentStorage.class);
        DocumentStorageService svc = storage(true, s3);
        assertEquals("s3", svc.mode());
        DocumentStorageService.StoredFile f = svc.upload(12L, "My Resume (final).pdf", PDF, "application/octet-stream");
        assertTrue(f.url().startsWith("s3:participant-documents/12/My_Resume__final_-") && f.url().endsWith(".pdf"), f.url());
        String key = f.url().substring(3);
        verify(s3).store(PDF, key, "application/pdf");   // the real type, not the browser's label
        when(s3.get(key)).thenReturn(PDF);
        DocumentStorageService.Retrieval r = svc.retrieve(f.url());
        assertArrayEquals(PDF, r.bytes());
        assertEquals("application/pdf", r.contentType());
        svc.delete(f.storagePath());
        verify(s3).delete(key);
    }

    @Test
    void withoutS3OrCloudinaryItsTheLocalDiskAndThatIsVisible() {
        assertEquals("local-disk", storage(false, mock(DocumentStorage.class)).mode());
    }

    @Test
    void onlyFilesInTheDocumentsFolderCanBeReadOrDeleted() {
        DocumentStorageService svc = storage(false, mock(DocumentStorage.class));
        assertNull(svc.retrieve("/etc/passwd"));
        assertNull(svc.retrieve("participant-documents/../../etc/passwd"));
        assertNull(svc.retrieve("backend/src/main/resources/application.properties"));
        svc.delete("/etc/hosts");   // refused quietly, nothing thrown
    }

    @Test
    void theRealFileTypeIsCheckedNotTheName() {
        assertEquals("application/pdf", DocumentStorageService.sniffContentType(PDF));
        assertEquals("image/png", DocumentStorageService.sniffContentType(PNG));
        assertEquals("image/jpeg", DocumentStorageService.sniffContentType(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        assertNull(DocumentStorageService.sniffContentType("<html><script>alert(1)</script>".getBytes()));
        assertNull(DocumentStorageService.sniffContentType(new byte[]{1}));
    }

    @Test
    void aReUploadKeepsTheOldFileUntilTheNewOneIsStored() {
        UserRepository users = mock(UserRepository.class);
        User pat = User.builder().id(10L).role(Role.builder().name("PARTICIPANT").build()).participantId("SAGE-2026-00001")
                .isActive(true).acknowledgmentComplete(true).build();
        when(users.findById(10L)).thenReturn(Optional.of(pat));
        ParticipantDocumentRepository docs = mock(ParticipantDocumentRepository.class);
        ParticipantDocument old = ParticipantDocument.builder().id(1L).userId(10L).documentType("RESUME")
                .storagePath("s3:participant-documents/10/old.pdf").fileUrl("s3:participant-documents/10/old.pdf").build();
        when(docs.findByUserIdAndDocumentType(10L, "RESUME")).thenReturn(new ArrayList<>(List.of(old)));
        when(docs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DocumentStorageService store = mock(DocumentStorageService.class);
        when(store.upload(anyLong(), anyString(), any(), any())).thenThrow(new RuntimeException("S3 is down"));
        DocumentService service = new DocumentService(docs, users, store, mock(WorkflowService.class),
                mock(RecordService.class), mock(ProfileCompletionService.class), mock(PermissionService.class),
                mock(EmailTemplateService.class));

        assertThrows(RuntimeException.class, () -> service.upload(10L, "RESUME",
                new MockMultipartFile("file", "new.pdf", "application/pdf", PDF)));
        verify(docs, never()).delete(any());
        verify(store, never()).delete(anyString());

        assertThrows(IllegalArgumentException.class, () -> service.upload(10L, "RESUME",
                new MockMultipartFile("file", "cv.pdf", "application/pdf", "<html>not a pdf</html>".getBytes())),
                "named .pdf, labelled PDF, but it isn't one");
    }
}
