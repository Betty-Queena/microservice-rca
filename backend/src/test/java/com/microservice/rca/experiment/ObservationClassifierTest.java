package com.microservice.rca.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import com.microservice.rca.diagnosis.domain.FaultSeverity;
import org.junit.jupiter.api.Test;

class ObservationClassifierTest {

    private static final long SLOW_THRESHOLD = 3000;

    @Test
    void fastSuccessIsNotAFault() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(200, null, 15, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("SUCCESS");
        assertThat(observation.faultObserved()).isFalse();
        assertThat(observation.severity()).isEqualTo(FaultSeverity.INFO);
    }

    @Test
    void slowSuccessIsRecordedAsSlowResponse() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(200, null, 3516, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("SLOW_RESPONSE");
        assertThat(observation.faultObserved()).isTrue();
        assertThat(observation.severity()).isEqualTo(FaultSeverity.WARNING);
    }

    @Test
    void timeoutUsesDownstreamErrorType() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(502, "TIMEOUT", 5328, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("DOWNSTREAM_TIMEOUT");
        assertThat(observation.faultObserved()).isTrue();
        assertThat(observation.severity()).isEqualTo(FaultSeverity.ERROR);
    }

    @Test
    void downstreamHttp5xxIsDistinguishedFromTimeout() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(502, "HTTP_5XX", 131, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("DOWNSTREAM_HTTP_5XX");
        assertThat(observation.faultObserved()).isTrue();
    }

    @Test
    void insufficientStockIsBusinessConflictNotSystemFault() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(409, null, 31, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("BUSINESS_CONFLICT");
        assertThat(observation.faultObserved()).isFalse();
        assertThat(observation.severity()).isEqualTo(FaultSeverity.INFO);
    }

    @Test
    void clientErrorIsRequestRejectedInsteadOfSystemFault() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(400, null, 20, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("REQUEST_REJECTED");
        assertThat(observation.faultObserved()).isFalse();
    }

    @Test
    void missingSkuIsRequestRejectedInsteadOfSystemFault() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(404, "HTTP_4XX", 18, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("REQUEST_REJECTED");
        assertThat(observation.faultObserved()).isFalse();
        assertThat(observation.severity()).isEqualTo(FaultSeverity.INFO);
    }

    @Test
    void downstream4xxWrappedAs502IsStillNotAFault() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(502, "HTTP_4XX", 18, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("REQUEST_REJECTED");
        assertThat(observation.faultObserved()).isFalse();
        assertThat(observation.severity()).isEqualTo(FaultSeverity.INFO);
    }

    @Test
    void orderServiceUnreachableIsRecorded() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(null, null, 2000, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("ORDER_SERVICE_UNREACHABLE");
        assertThat(observation.faultObserved()).isTrue();
    }

    @Test
    void orderServiceServerErrorIsRecorded() {
        ObservationClassifier.Observation observation =
                ObservationClassifier.classify(500, null, 40, SLOW_THRESHOLD);

        assertThat(observation.outcome()).isEqualTo("SERVER_ERROR");
        assertThat(observation.faultObserved()).isTrue();
        assertThat(observation.severity()).isEqualTo(FaultSeverity.ERROR);
    }
}
