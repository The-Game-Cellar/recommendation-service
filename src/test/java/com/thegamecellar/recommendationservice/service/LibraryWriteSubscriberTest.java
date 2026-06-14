package com.thegamecellar.recommendationservice.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryWriteSubscriberTest {

    @Test
    void acceptsLowercaseUuid() {
        assertTrue(LibraryWriteSubscriber.isValidUserId("a1b2c3d4-e5f6-7890-abcd-ef0123456789"));
    }

    @Test
    void acceptsUppercaseUuid() {
        assertTrue(LibraryWriteSubscriber.isValidUserId("A1B2C3D4-E5F6-7890-ABCD-EF0123456789"));
    }

    @Test
    void rejectsNull() {
        assertFalse(LibraryWriteSubscriber.isValidUserId(null));
    }

    @Test
    void rejectsEmpty() {
        assertFalse(LibraryWriteSubscriber.isValidUserId(""));
    }

    @Test
    void rejectsArbitraryString() {
        assertFalse(LibraryWriteSubscriber.isValidUserId("not-a-uuid"));
    }

    @Test
    void rejectsSqlInjectionAttempt() {
        assertFalse(LibraryWriteSubscriber.isValidUserId("'; DROP TABLE compute_queue; --"));
    }

    @Test
    void rejectsLogInjectionAttempt() {
        assertFalse(LibraryWriteSubscriber.isValidUserId("a1b2c3d4-e5f6-7890-abcd-ef0123456789\nFAKE LOG"));
    }

    @Test
    void rejectsExtraCharacters() {
        assertFalse(LibraryWriteSubscriber.isValidUserId("a1b2c3d4-e5f6-7890-abcd-ef0123456789X"));
    }

    @Test
    void rejectsMissingHyphens() {
        assertFalse(LibraryWriteSubscriber.isValidUserId("a1b2c3d4e5f67890abcdef0123456789"));
    }

    @Test
    void rejectsNonHexChars() {
        assertFalse(LibraryWriteSubscriber.isValidUserId("g1b2c3d4-e5f6-7890-abcd-ef0123456789"));
    }
}
