package com.beanLoyal.backend.rewards;

import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PendingRewardTest {
    Firestore db = mock(Firestore.class);
    CollectionReference collection = mock(CollectionReference.class);
    Query owned = mock(Query.class), pending = mock(Query.class), bounded = mock(Query.class);
    QuerySnapshot snapshot = mock(QuerySnapshot.class);
    QueryDocumentSnapshot doc = mock(QueryDocumentSnapshot.class);
    Instant now = Instant.parse("2026-09-11T12:00:00Z");
    RedeemCodeService service = new RedeemCodeService(db, null, null, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(db.collection("redeem_codes")).thenReturn(collection);
        when(collection.whereEqualTo("uid", "alice")).thenReturn(owned);
        when(owned.whereEqualTo("status", "pending")).thenReturn(pending);
        when(pending.limit(1)).thenReturn(bounded);
        when(bounded.get()).thenReturn(ApiFutures.immediateFuture(snapshot));
        when(snapshot.getDocuments()).thenReturn(List.of(doc));
        when(doc.getId()).thenReturn("QR23456789");
        when(doc.getLong("cost")).thenReturn(50L);
    }

    @Test
    void lookupIsBoundedAndOwnedAndRestoresCode() throws Exception {
        when(doc.getTimestamp("expiresAt")).thenReturn(Timestamp.ofTimeSecondsAndNanos(now.plusSeconds(60).getEpochSecond(), 0));
        var result = service.pendingReward("alice").pending();
        assertThat(result.code()).isEqualTo("QR23456789");
        assertThat(result.status()).isEqualTo("pending");
        assertThat(result.expiresAtEpochMs()).isEqualTo(now.plusSeconds(60).toEpochMilli());
        verify(collection).whereEqualTo("uid", "alice");
        verify(owned).whereEqualTo("status", "pending");
        verify(pending).limit(1);
    }

    @Test
    void expiredCodeRemainsRecoverableForRefundWithoutUsableQr() throws Exception {
        when(doc.getTimestamp("expiresAt")).thenReturn(Timestamp.ofTimeSecondsAndNanos(now.getEpochSecond(), 0));
        assertThat(service.pendingReward("alice").pending().status()).isEqualTo("awaiting_refund");
    }

    @Test
    void noPendingReturnsExplicitEmptyResult() throws Exception {
        when(snapshot.isEmpty()).thenReturn(true);
        assertThat(service.pendingReward("alice").pending()).isNull();
    }
}
