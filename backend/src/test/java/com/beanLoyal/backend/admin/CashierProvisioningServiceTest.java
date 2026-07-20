package com.beanLoyal.backend.admin;

import com.beanLoyal.backend.common.ApiException;
import com.beanLoyal.backend.common.IdempotencyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers replay, changed-input rejection, and recovery between provisioning stages. */
class CashierProvisioningServiceTest {

    @Test
    void missingKeyIsRejectedBeforeAnySideEffect() {
        FakeLedger ledger = new FakeLedger();
        FakeAccounts accounts = new FakeAccounts();
        CashierProvisioningService service = service(ledger, accounts);

        assertThatThrownBy(() -> service.provision("admin-1", null, request()))
                .isInstanceOf(IdempotencyException.class)
                .extracting(e -> ((IdempotencyException) e).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(ledger.reserveCalls).isZero();
        assertThat(accounts.ensureCalls).isZero();
    }

    @Test
    void completedRetryReplaysWithoutRepeatingProvisioning() {
        FakeLedger ledger = new FakeLedger();
        FakeAccounts accounts = new FakeAccounts();
        CashierProvisioningService service = service(ledger, accounts);

        CashierProvisioningService.ProvisioningResult first =
                service.provision("admin-1", "request-1", request());
        CashierProvisioningService.ProvisioningResult replay =
                service.provision("admin-1", "request-1", request());

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response()).isEqualTo(first.response());
        assertThat(first.response().uid()).startsWith("cashier-").hasSize(72);
        assertThat(accounts.ensureCalls).isEqualTo(1);
        assertThat(accounts.grantCalls).isEqualTo(1);
        assertThat(ledger.profileWrites).isEqualTo(1);
        assertThat(ledger.completionWrites).isEqualTo(1);
    }

    @Test
    void sameKeyWithChangedBodyIsRejected() {
        FakeLedger ledger = new FakeLedger();
        CashierProvisioningService service = service(ledger, new FakeAccounts());
        service.provision("admin-1", "request-1", request());

        CreateCashierRequest changed = new CreateCashierRequest(
                "different@example.com", "secret1", "Cashier One");
        assertThatThrownBy(() -> service.provision("admin-1", "request-1", changed))
                .isInstanceOf(IdempotencyException.class)
                .extracting(e -> ((IdempotencyException) e).getCode())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void retryAfterClaimFailureResumesWithoutDuplicatingProfileOrAuditStage() {
        FakeLedger ledger = new FakeLedger();
        FakeAccounts accounts = new FakeAccounts();
        accounts.remainingGrantFailures = 1;
        CashierProvisioningService service = service(ledger, accounts);

        assertThatThrownBy(() -> service.provision("admin-1", "request-1", request()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CASHIER_CLAIM_FAILED");

        CashierProvisioningService.ProvisioningResult resumed =
                service.provision("admin-1", "request-1", request());
        assertThat(resumed.replayed()).isFalse();
        assertThat(accounts.ensureCalls).isEqualTo(2);
        assertThat(accounts.grantCalls).isEqualTo(2);
        assertThat(ledger.profileWrites).isEqualTo(1);
        assertThat(ledger.completionWrites).isEqualTo(1);
    }

    @Test
    void reservationStoresOnlyAHashOfThePasswordBearingRequest() {
        FakeLedger ledger = new FakeLedger();
        CashierProvisioningService service = service(ledger, new FakeAccounts());
        service.provision("admin-1", "request-1", request());

        assertThat(ledger.requestHash).hasSize(64).doesNotContain("secret1");
    }

    private static CashierProvisioningService service(FakeLedger ledger, FakeAccounts accounts) {
        return new CashierProvisioningService(ledger, accounts, new ObjectMapper());
    }

    private static CreateCashierRequest request() {
        return new CreateCashierRequest(" cashier@example.com ", "secret1", " Cashier One ");
    }

    private static final class FakeLedger implements CashierProvisioningService.ProvisioningLedger {
        private String requestHash;
        private String targetUid;
        private String status;
        private int reserveCalls;
        private int profileWrites;
        private int completionWrites;

        @Override
        public CashierProvisioningService.Reservation reserve(
                String operationId, String actorUid, String targetUid,
                String requestHash, String email) {
            reserveCalls++;
            if (this.requestHash == null) {
                this.requestHash = requestHash;
                this.targetUid = targetUid;
                this.status = CashierProvisioningService.STATUS_RESERVED;
            } else if (!this.requestHash.equals(requestHash)) {
                throw IdempotencyException.keyReused();
            }
            assertThat(targetUid).isEqualTo(this.targetUid);
            return new CashierProvisioningService.Reservation(
                    CashierProvisioningService.STATUS_COMPLETED.equals(status));
        }

        @Override
        public void prepareProfile(String operationId, String requestHash, String actorUid,
                                   String targetUid, String email, String name) {
            assertThat(requestHash).isEqualTo(this.requestHash);
            if (CashierProvisioningService.STATUS_RESERVED.equals(status)) {
                profileWrites++;
                status = CashierProvisioningService.STATUS_PROFILE_READY;
            }
        }

        @Override
        public void complete(String operationId, String requestHash, String responseBody) {
            assertThat(status).isEqualTo(CashierProvisioningService.STATUS_PROFILE_READY);
            completionWrites++;
            status = CashierProvisioningService.STATUS_COMPLETED;
        }
    }

    private static final class FakeAccounts implements CashierProvisioningService.CashierAccounts {
        private int ensureCalls;
        private int grantCalls;
        private int remainingGrantFailures;

        @Override
        public void ensureAccount(String uid, String email, String password, String name) {
            ensureCalls++;
        }

        @Override
        public void grantCashierRole(String uid) {
            grantCalls++;
            if (remainingGrantFailures > 0) {
                remainingGrantFailures--;
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "CASHIER_CLAIM_FAILED", "temporary failure");
            }
        }
    }
}
