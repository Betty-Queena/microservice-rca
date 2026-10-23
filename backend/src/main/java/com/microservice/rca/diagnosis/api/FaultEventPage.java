package com.microservice.rca.diagnosis.api;

import java.util.List;

public record FaultEventPage(List<FaultEventView> items, long total, int page, int size) {
}
