package com.example.matchingservice.service;

import com.example.matchingservice.dto.*;
import com.example.matchingservice.event.EventEnvelope;
import java.util.*;

public interface MatchingService {

    MatchResponse matchDriver(UUID requestId, String correlationId);

    RematchResponse rematchDriver(UUID requestId, String correlationId);

    void handleRideRequested(EventEnvelope<Map<String, Object>> event);

    void handleTripCancelled(EventEnvelope<Map<String, Object>> event);

    void retryOrExpire(UUID requestId);

}
