package com.example.pricingservice.client;

import com.example.pricingservice.dto.request.LocationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Driving distance along the streets from an OSRM routing server (OpenStreetMap data, the same map the web pages show).
 * Empty when the server cannot answer, so pricing falls back to its straight-line estimate instead of failing the quote.
 */
@Slf4j
@Component
public class RoutingClient {

    private static final int MAX_CACHED_ROUTES = 2000;

    private final RestClient client;
    private final ObjectMapper objectMapper;

    // shortcut: per-instance cache, because the public OSRM server allows about 1 request/s and a quote is asked
    // again for the same two points (booking, map redraw, load test); a self-hosted OSRM makes it optional
    private final Map<String, Route> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Route> eldest) {
            return size() > MAX_CACHED_ROUTES;
        }
    };

    /** Road distance in km and the road as [latitude, longitude] points, ready for a map line. */
    public record Route(double distanceKm, List<List<Double>> points) {
    }

    public RoutingClient(@Value("${pricing.routing.url:https://router.project-osrm.org}") String baseUrl,
                         @Value("${pricing.routing.timeout-ms:3000}") long timeoutMs, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // HTTP/1.1 client: the JDK HttpClient's HTTP/2 failed to read the public OSRM server's answers
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeoutMs);
        factory.setReadTimeout((int) timeoutMs);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Optional<Route> drivingRoute(LocationDto from, LocationDto to) {
        String key = String.format(Locale.ROOT, "%.5f,%.5f;%.5f,%.5f",
                from.getLongitude(), from.getLatitude(), to.getLongitude(), to.getLatitude());
        synchronized (cache) {
            Route cached = cache.get(key);
            if (cached != null) {
                return Optional.of(cached);
            }
        }
        try {
            // Read as text: a RestClient built here has no Jackson 3 converter for JsonNode
            String text = client.get()
                    .uri("/route/v1/driving/{fromLng},{fromLat};{toLng},{toLat}?overview=full&geometries=geojson",
                            from.getLongitude(), from.getLatitude(), to.getLongitude(), to.getLatitude())
                    .retrieve()
                    .body(String.class);
            JsonNode body = text == null ? null : objectMapper.readTree(text);
            JsonNode route = body == null ? null : body.path("routes").path(0);
            if (route == null || !"Ok".equals(body.path("code").asText()) || !route.path("distance").isNumber()) {
                log.warn("Routing server found no road route: from={}, to={}, code={}", from, to, body == null ? null : body.path("code").asText());
                return Optional.empty();
            }
            List<List<Double>> points = new ArrayList<>();
            for (JsonNode coordinate : route.path("geometry").path("coordinates")) {
                points.add(List.of(coordinate.get(1).asDouble(), coordinate.get(0).asDouble()));
            }
            Route result = new Route(route.path("distance").asDouble() / 1000, points);
            synchronized (cache) {
                cache.put(key, result);
            }
            return Optional.of(result);
        } catch (RuntimeException e) {
            log.warn("Routing server unavailable, using the straight-line estimate: {}", NestedExceptionUtils.getMostSpecificCause(e).toString());
            return Optional.empty();
        }
    }
}
