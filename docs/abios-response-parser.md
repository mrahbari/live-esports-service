# AbiosResponseParser

## 1. Overview
`AbiosResponseParser` is a central component in the integration layer of the application. Its primary role is to serve as an **Adapter**, transforming raw JSON data (received as byte arrays from the Abios external API) into structured internal Java models (`AbiosEnrichedDocument`, `List<T>`, etc.). It bridges the gap between the flexible, version-dependent JSON structures of the upstream API and the strongly-typed requirements of the system.

## 2. Responsibilities
- **Multi-Shape Parsing:** Handles varying JSON response formats, such as raw arrays or objects containing a `series` or `data` field.
- **Atlas Compatibility:** Specifically supports the "Atlas" style responses where data is wrapped in a `data` envelope.
- **Safe Logging:** Provides mechanisms to log raw upstream responses for debugging without overwhelming logs or risking memory issues with large payloads.
- **Observability:** Analyzes and logs the "shape" of parsed data (e.g., count of teams and players) to provide insights into data quality and completeness in production.

## 3. Key Methods

### `parseSeriesPayload`
- **Purpose:** Specifically designed to parse the main Series data.
- **Input/Output:** Receives `byte[] payload`; returns `AbiosEnrichedDocument`.
- **Behavior:** It checks if the root of the JSON is an array. If so, it maps it to a list of series nodes. If it's an object, it maps it directly to the document model.
- **Edge Cases:** Returns an empty `AbiosEnrichedDocument` if the payload is null or empty.

### `parseAtlasListPayload`
- **Purpose:** A generic method for parsing lists from the Atlas API.
- **Input/Output:** Receives `byte[] payload`, `String pathForLog`, and `TypeReference<List<T>> typeRef`; returns `List<T>`.
- **Behavior:** It looks for a JSON array either at the root or under a `data` field.
- **Edge Cases:** Returns an empty list if the payload is empty or if no array is found where expected.

### `logRawUpstreamResponse`
- **Purpose:** Logs the raw JSON received from the API.
- **Input/Output:** Receives `byte[] payload`; void return.
- **Behavior:** Converts the payload to a UTF-8 string and logs it. It checks `AbiosProperties` to see if logging is enabled.
- **Edge Cases:** If the payload exceeds 512,000 characters, it truncates the output to prevent log bloating.

### `logParsedSeriesShape`
- **Purpose:** Logs statistical metadata about the parsed document.
- **Input/Output:** Receives `AbiosEnrichedDocument doc`; void return.
- **Behavior:** Iterates through the series and teams to count participants, players, and identified teams.
- **Edge Cases:** Logs "document=null" if the input is null.

## 4. Supported Payload Formats

### Raw JSON Array
```json
[
  { "id": "123", "name": "Series A" },
  { "id": "124", "name": "Series B" }
]
```

### Envelope with "series"
```json
{
  "series": [
    { "id": "123", "name": "Series A" }
  ]
}
```

### Envelope with "data" (Atlas Style)
```json
{
  "data": [
    { "id": "123", "name": "Series A" }
  ]
}
```

## 5. Error Handling Strategy
- **Resilience to Empty Data:** If `null` or an empty byte array is provided, the parser returns a safe "empty" object (e.g., `new AbiosEnrichedDocument()` or `List.of()`) instead of throwing a `NullPointerException`.
- **Unexpected Structures:** If `parseAtlasListPayload` encounters a structure that is neither an array nor contains a `data` array, it logs a warning and returns an empty list.
- **IOException:** Methods are declared to throw `IOException`, allowing the calling service to decide how to handle network-level or format-level failures.

## 6. Logging Strategy
- **Configuration-Driven:** Logging is controlled by `abios.log-upstream-payload` in `AbiosProperties`.
- **Dynamic Levels:** If payload logging is explicitly enabled, it uses `INFO` level. Otherwise, it defaults to `DEBUG` (meaning it only appears if the logger is set to DEBUG).
- **Safety Truncation:** Large responses are truncated at 512KB (`MAX_RESPONSE_BODY_LOG_CHARS`) to protect system resources.

## 7. Observability & Metrics
The `logParsedSeriesShape` method provides vital "data quality" metrics:
- It counts how many series were found.
- It counts how many participant slots are filled and how many have valid Team IDs.
- It counts how many players are present in the rosters.
This is useful in production to detect if the upstream API is sending "hollow" data (e.g., a series with no teams or players) which might indicate an upstream issue or a change in data permissions.

## 8. Design Decisions
- **Jackson ObjectMapper:** Utilized as the industry-standard JSON processor for Java.
- **JsonNode for Flexibility:** The parser uses `JsonNode` to inspect the JSON structure (e.g., `root.isArray()`) before deciding how to map it, providing more flexibility than strict POJO mapping.
- **TypeReference:** Uses `TypeReference` to preserve generic type information (like `List<AbiosSeriesNode>`) during conversion, avoiding "unchecked cast" warnings.

## 9. Potential Improvements
- **Explicit Error Handling:** Wrapping `objectMapper` calls in try/catch blocks within the parser to provide more context-specific custom exceptions.
- **Masking:** Implementing a regex-based masker to hide sensitive information (like API keys) if they ever appear in the payload logs.
- **Performance:** For extremely large payloads, switching from byte arrays to `InputStream` would reduce memory pressure.
- **Metrics Integration:** Instead of just logging the "shape", these counts could be exported to **Micrometer** or **Prometheus** for real-time dashboarding.

## 10. Example Usage
```java
@Service
public class SeriesService {
    private final AbiosDataGateway gateway;
    private final AbiosResponseParser parser;

    public void syncSeries() {
        byte[] rawData = gateway.fetchRawSeries();
        try {
            // 1. Log the raw response (if enabled)
            parser.logRawUpstreamResponse(rawData);
            
            // 2. Parse the payload
            AbiosEnrichedDocument doc = parser.parseSeriesPayload(rawData);
            
            // 3. Process the data...
        } catch (IOException e) {
            log.error("Failed to parse Abios response", e);
        }
    }
}
```

## 11. Dependencies
- **Jackson (databind/core):** For JSON processing and tree traversal.
- **Lombok:** Used for `@RequiredArgsConstructor` and `@Slf4j` to keep the code clean.
- **Spring Framework:** Marked with `@Component` for dependency injection.

## 12. Summary
`AbiosResponseParser` is a critical component that ensures the application remains resilient and observant while consuming external data. By handling multiple JSON shapes and providing detailed observability logs, it minimizes the risk of integration failures and helps developers monitor data health in real-time.
