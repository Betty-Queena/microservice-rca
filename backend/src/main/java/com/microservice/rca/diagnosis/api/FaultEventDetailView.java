package com.microservice.rca.diagnosis.api;

import java.util.List;

public record FaultEventDetailView(FaultEventView event, List<DiagnosisTaskView> diagnosisTasks) {
}
