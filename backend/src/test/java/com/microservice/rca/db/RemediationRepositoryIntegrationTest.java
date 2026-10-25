package com.microservice.rca.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.microservice.rca.agent.DiagnosisTaskRecovery;
import com.microservice.rca.diagnosis.domain.DiagnosisResultStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisStatus;
import com.microservice.rca.diagnosis.domain.DiagnosisTask;
import com.microservice.rca.diagnosis.domain.FaultEvent;
import com.microservice.rca.diagnosis.domain.FaultEventStatus;
import com.microservice.rca.diagnosis.domain.FaultSeverity;
import com.microservice.rca.diagnosis.repository.DiagnosisTaskRepository;
import com.microservice.rca.diagnosis.repository.FaultEventRepository;
import com.microservice.rca.remediation.domain.RecoveryCodes;
import com.microservice.rca.remediation.domain.RemediationAction;
import com.microservice.rca.remediation.domain.RemediationActionType;
import com.microservice.rca.remediation.domain.RemediationStatus;
import com.microservice.rca.remediation.repository.RemediationActionRepository;
import com.microservice.rca.testsupport.TestDatabaseGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 处置记录的 JPA 映射与数据库唯一约束验证。
 *
 * <p>默认不执行，只有显式设置 RCA_V5_INTEGRATION=true 且连接到 rca_v5_* 临时库时运行。</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "rca.remediation.recovery.enabled=false",
                "rca.test.expected-database-prefix=rca_v5_"
        })
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RCA_V5_INTEGRATION", matches = "true")
class RemediationRepositoryIntegrationTest {

    /**
     * 屏蔽启动恢复监听器，避免上下文启动阶段扫描并修改诊断任务。
     */
    @MockitoBean
    private DiagnosisTaskRecovery diagnosisTaskRecovery;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FaultEventRepository faultEventRepository;

    @Autowired
    private DiagnosisTaskRepository diagnosisTaskRepository;

    @Autowired
    private RemediationActionRepository remediationRepository;

    private final List<UUID> extraEventIds = new ArrayList<>();

    private UUID eventId;
    private UUID taskId;

    @BeforeEach
    void createEventAndTask() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertThat(database)
                .as("处置仓库集成测试只能连接临时验证库")
                .startsWith("rca_v5_");

        EventTask main = insertEventAndTask("Remediation repository integration test");
        eventId = main.eventId();
        taskId = main.taskId();
    }

    @AfterEach
    void cleanup() {
        cleanupEvent(eventId);
        extraEventIds.forEach(this::cleanupEvent);
        extraEventIds.clear();
    }

    private EventTask insertEventAndTask(String summary) {
        FaultEvent event = new FaultEvent();
        event.setServiceName("inventory-service");
        event.setEventType("DOWNSTREAM_HTTP_5XX");
        event.setSeverity(FaultSeverity.ERROR);
        event.setSummary(summary);
        event.setStatus(FaultEventStatus.OPEN);
        event.setOccurredAt(Instant.now());
        event.setSource("EXPERIMENT");
        event.setExperimentId(UUID.randomUUID());
        event = faultEventRepository.saveAndFlush(event);

        DiagnosisTask task = new DiagnosisTask();
        task.setFaultEventId(event.getId());
        task.setStatus(DiagnosisStatus.SUCCEEDED);
        task.setResultStatus(DiagnosisResultStatus.ROOT_CAUSE_IDENTIFIED);
        task.setTriggerSource("AGENT");
        task.setGoal(summary);
        task = diagnosisTaskRepository.saveAndFlush(task);
        return new EventTask(event.getId(), task.getId());
    }

    private EventTask createExtraEventAndTask(String summary) {
        EventTask created = insertEventAndTask(summary);
        extraEventIds.add(created.eventId());
        return created;
    }

    private void cleanupEvent(UUID id) {
        if (id != null) {
            jdbc.update("delete from remediation_action where fault_event_id = ?", id);
            jdbc.update("delete from diagnosis_task where fault_event_id = ?", id);
            jdbc.update("delete from fault_event where id = ?", id);
        }
    }

    private record EventTask(UUID eventId, UUID taskId) {
    }

    @Test
    void findsByIdempotencyKeyAndOrdersHistoryDescending() {
        Instant older = Instant.now().minusSeconds(60);
        RemediationAction first = action("idem-1", RemediationStatus.SUCCEEDED, older);
        RemediationAction second = action("idem-2", RemediationStatus.ACTION_FAILED, Instant.now());
        remediationRepository.saveAndFlush(first);
        remediationRepository.saveAndFlush(second);

        assertThat(remediationRepository.findByFaultEventIdAndIdempotencyKey(eventId, "idem-1"))
                .isPresent()
                .get()
                .extracting(RemediationAction::getId)
                .isEqualTo(first.getId());

        List<RemediationAction> history =
                remediationRepository.findByFaultEventIdOrderByRequestedAtDesc(eventId);
        assertThat(history).extracting(RemediationAction::getId)
                .containsExactly(second.getId(), first.getId());

        assertThat(remediationRepository.findFirstByFaultEventIdAndStatusInOrderByRequestedAtDesc(
                eventId, List.of(RemediationStatus.ACTION_FAILED, RemediationStatus.SUCCEEDED)))
                .isPresent()
                .get()
                .extracting(RemediationAction::getId)
                .isEqualTo(second.getId());

        assertThat(remediationRepository.findFirstByFaultEventIdOrderByRequestedAtDesc(eventId))
                .isPresent()
                .get()
                .extracting(RemediationAction::getId)
                .isEqualTo(second.getId());
    }

    @Test
    void activeUniqueIndexRejectsSecondActiveRecord() {
        remediationRepository.saveAndFlush(
                action("idem-1", RemediationStatus.PENDING, Instant.now()));
        RemediationAction second = action("idem-2", RemediationStatus.EXECUTING, Instant.now());

        assertThatThrownBy(() -> remediationRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyIsUniquePerEvent() {
        remediationRepository.saveAndFlush(
                action("same-key", RemediationStatus.SUCCEEDED, Instant.now()));
        RemediationAction duplicate = action(
                "same-key", RemediationStatus.ACTION_FAILED, Instant.now());

        assertThatThrownBy(() -> remediationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolveIfOpenOnlyUpdatesOpenEventWithResolvedAt() {
        Instant now = Instant.now();

        assertThat(faultEventRepository.resolveIfOpen(
                eventId, FaultEventStatus.OPEN, FaultEventStatus.RESOLVED, now))
                .isEqualTo(1);
        Integer resolved = jdbc.queryForObject("""
                select count(*) from fault_event
                 where id = ? and status = 'RESOLVED' and resolved_at is not null
                """, Integer.class, eventId);
        assertThat(resolved).isEqualTo(1);

        assertThat(faultEventRepository.resolveIfOpen(
                eventId, FaultEventStatus.OPEN, FaultEventStatus.RESOLVED, now))
                .isZero();
    }

    @Test
    void findInterruptedBeforeSelectsOnlyOldActiveRecords() {
        Instant cutoff = Instant.parse("2026-10-24T10:00:00Z");
        RemediationAction oldPending = action(eventId, taskId, "old-pending",
                RemediationStatus.PENDING, cutoff.minusSeconds(60));
        RemediationAction oldTerminal = action(eventId, taskId, "old-terminal",
                RemediationStatus.SUCCEEDED, cutoff.minusSeconds(60));

        EventTask second = createExtraEventAndTask("second recovery event");
        RemediationAction oldExecuting = action(second.eventId(), second.taskId(),
                "old-executing", RemediationStatus.EXECUTING, cutoff.minusSeconds(120));
        oldExecuting.setStartedAt(cutoff.minusSeconds(30));

        EventTask third = createExtraEventAndTask("third recovery event");
        RemediationAction newPending = action(third.eventId(), third.taskId(),
                "new-pending", RemediationStatus.PENDING, cutoff.plusSeconds(60));

        remediationRepository.saveAndFlush(oldPending);
        remediationRepository.saveAndFlush(oldTerminal);
        remediationRepository.saveAndFlush(oldExecuting);
        remediationRepository.saveAndFlush(newPending);

        List<RemediationAction> found = remediationRepository.findInterruptedBefore(
                List.of(RemediationStatus.PENDING, RemediationStatus.EXECUTING), cutoff);

        // 只断言本用例关心的记录：临时库可能残留其他测试的活跃记录。
        assertThat(found).extracting(RemediationAction::getId)
                .contains(oldPending.getId(), oldExecuting.getId())
                .doesNotContain(oldTerminal.getId(), newPending.getId());
    }

    @Test
    void markInterruptedUsesStatusAndVersionCasAndPreservesResults() {
        Instant completedAt = Instant.parse("2026-10-24T10:00:00Z");
        RemediationAction executing = action(eventId, taskId, "cas-key",
                RemediationStatus.EXECUTING, Instant.parse("2026-10-24T09:58:00Z"));
        executing.setStartedAt(Instant.parse("2026-10-24T09:58:30Z"));
        executing.setActionResult("{\"success\":true,\"outcome\":\"DISABLED\"}");
        executing.setVerificationResult("{\"result\":\"PASS\",\"verified\":true}");
        executing = remediationRepository.saveAndFlush(executing);
        long version = executing.getVersion();
        String audit = "[{\"step\":\"RECOVERY_MARKED\"}]";

        int updated = remediationRepository.markInterrupted(
                executing.getId(),
                RemediationStatus.EXECUTING,
                version,
                RemediationStatus.RECOVERY_REQUIRED,
                RecoveryCodes.VERIFICATION_UNKNOWN,
                "verification result unknown",
                audit,
                completedAt);

        assertThat(updated).isEqualTo(1);
        RemediationAction recovered = remediationRepository.findById(executing.getId())
                .orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(RemediationStatus.RECOVERY_REQUIRED);
        assertThat(recovered.getFailureCode()).isEqualTo(RecoveryCodes.VERIFICATION_UNKNOWN);
        assertThat(recovered.getFailureReason()).isEqualTo("verification result unknown");
        assertThat(recovered.getActionResult()).contains("\"success\"");
        assertThat(recovered.getVerificationResult()).contains("\"PASS\"");
        assertThat(recovered.getAuditTrace()).contains("RECOVERY_MARKED");
        assertThat(recovered.isEventStatusUpdated()).isFalse();
        assertThat(recovered.getCompletedAt()).isNotNull();
        assertThat(recovered.getVersion()).isEqualTo(version + 1);

        int repeated = remediationRepository.markInterrupted(
                executing.getId(),
                RemediationStatus.EXECUTING,
                version,
                RemediationStatus.RECOVERY_REQUIRED,
                RecoveryCodes.VERIFICATION_UNKNOWN,
                "verification result unknown",
                audit,
                completedAt);
        assertThat(repeated).isZero();
        assertThat(remediationRepository.findById(executing.getId()).orElseThrow().getVersion())
                .isEqualTo(version + 1);

        int staleVersion = remediationRepository.markInterrupted(
                executing.getId(),
                RemediationStatus.RECOVERY_REQUIRED,
                version,
                RemediationStatus.RECOVERY_REQUIRED,
                RecoveryCodes.STATE_INCONSISTENT,
                "wrong version",
                audit,
                completedAt);
        assertThat(staleVersion).isZero();
    }

    @Test
    void existsByFaultEventIdAndStatusOnlyMatchesRequestedStatus() {
        remediationRepository.saveAndFlush(
                action("idem-succeeded", RemediationStatus.SUCCEEDED, Instant.now()));
        remediationRepository.saveAndFlush(
                action("idem-failed", RemediationStatus.ACTION_FAILED, Instant.now()));

        assertThat(remediationRepository.existsByFaultEventIdAndStatus(
                eventId, RemediationStatus.SUCCEEDED)).isTrue();
        assertThat(remediationRepository.existsByFaultEventIdAndStatus(
                eventId, RemediationStatus.ACTION_FAILED)).isTrue();
        assertThat(remediationRepository.existsByFaultEventIdAndStatus(
                eventId, RemediationStatus.RECOVERY_REQUIRED)).isFalse();
        assertThat(remediationRepository.existsByFaultEventIdAndStatus(
                eventId, RemediationStatus.VERIFICATION_INCONCLUSIVE)).isFalse();
    }

    @Test
    void existsByFaultEventIdAndStatusDoesNotLeakAcrossEvents() {
        EventTask other = createExtraEventAndTask("other event remediation success");
        remediationRepository.saveAndFlush(action(other.eventId(), other.taskId(),
                "other-succeeded", RemediationStatus.SUCCEEDED, Instant.now()));

        assertThat(remediationRepository.existsByFaultEventIdAndStatus(
                eventId, RemediationStatus.SUCCEEDED)).isFalse();
        assertThat(remediationRepository.existsByFaultEventIdAndStatus(
                other.eventId(), RemediationStatus.SUCCEEDED)).isTrue();
    }

    @Test
    void succeededWithEventNotResolvedStillCountsAsSucceeded() {
        RemediationAction succeeded = action(
                "idem-open-event", RemediationStatus.SUCCEEDED, Instant.now());
        succeeded.setEventStatusUpdated(false);
        remediationRepository.saveAndFlush(succeeded);

        assertThat(remediationRepository.existsByFaultEventIdAndStatus(
                eventId, RemediationStatus.SUCCEEDED)).isTrue();
        assertThat(faultEventRepository.findById(eventId).orElseThrow().getStatus())
                .isEqualTo(FaultEventStatus.OPEN);
    }

    private RemediationAction action(String key, RemediationStatus status, Instant requestedAt) {
        return action(eventId, taskId, key, status, requestedAt);
    }

    private RemediationAction action(UUID targetEventId,
                                     UUID targetTaskId,
                                     String key,
                                     RemediationStatus status,
                                     Instant requestedAt) {
        RemediationAction action = new RemediationAction();
        action.setFaultEventId(targetEventId);
        action.setDiagnosisTaskId(targetTaskId);
        action.setActionType(RemediationActionType.DISABLE_EXPERIMENT_FAULT);
        action.setTargetService("inventory-service");
        action.setExperimentId(UUID.randomUUID());
        action.setStatus(status);
        action.setRequestedBy("integration-test");
        action.setIdempotencyKey(key);
        action.setRequestedAt(requestedAt);
        action.setEventStatusUpdated(false);
        return action;
    }
}
