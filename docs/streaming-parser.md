# Streaming Parser Migration Guide

Replacing the tree-model JSON parse path with a streaming one to eliminate the intermediate
`byte[]` buffer and `JsonNode` tree from heap during large Atlas responses.

---

## Why the Current Approach Costs 3× Payload Memory

For every upstream response, there are three simultaneous heap allocations today:

```
HTTP response body → byte[]           (~1× payload)
objectMapper.readTree(payload)        (~2× payload — full JsonNode tree)
objectMapper.convertValue(root, ...)  (~3× payload — typed POJOs)
```

At 300 live series with full includes, that can be several hundred KB × 3 at peak. The streaming
path drops this to roughly 1× — only the typed POJO list remains after the parser finishes.

---

## Change 1 — `AbiosDataGateway`: stream instead of buffering to `byte[]`

**Current** (`fetchSeries`):
```java
byte[] payload = abiosRestClient.get()
    .uri(uri).headers(this::setAuth)
    .retrieve()
    ...
    .body(byte[].class);                         // buffers entire body

responseParser.logRawUpstreamResponse(payload);
return responseParser.parseSeriesPayload(payload);
```

**Replace with** `.body(InputStream.class, ...)` so the stream is consumed during parsing:
```java
return abiosRestClient.get()
    .uri(uri).headers(this::setAuth)
    .retrieve()
    .onStatus(...)                               // keep existing status handlers
    .body(InputStream.class, (inputStream) -> {
        try {
            return responseParser.parseSeriesPayloadStreaming(inputStream);
        } catch (IOException e) {
            throw new AbiosUpstreamTransientException(
                "Failed to parse series JSON: " + e.getMessage(), e);
        }
    });
```

Spring's `RestClient` closes the `InputStream` after the lambda returns — no manual cleanup needed.

Apply the same change to `fetchBatch`: replace the `byte[]` body + `parseAtlasListPayload` call
with `parseAtlasListPayloadStreaming`.

---

## Change 2 — `AbiosResponseParser`: add streaming parse methods

Add these two methods alongside the existing ones (keep the old methods for mock/test paths):

```java
public AbiosEnrichedDocument parseSeriesPayloadStreaming(InputStream in) throws IOException {
    List<AbiosSeriesNode> series = new ArrayList<>();
    try (JsonParser parser = objectMapper.getFactory().createParser(in)) {
        JsonToken first = parser.nextToken();
        if (first == JsonToken.START_ARRAY) {
            // bare array: [ {...}, {...} ]
            MappingIterator<AbiosSeriesNode> it =
                objectMapper.readValues(parser, AbiosSeriesNode.class);
            while (it.hasNextValue()) series.add(it.nextValue());
        } else {
            // envelope: { "data": [...] } or { "series": [...] }
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    if ("data".equals(field) || "series".equals(field)) {
                        parser.nextToken(); // advance to START_ARRAY
                        MappingIterator<AbiosSeriesNode> it =
                            objectMapper.readValues(parser, AbiosSeriesNode.class);
                        while (it.hasNextValue()) series.add(it.nextValue());
                        break;
                    }
                }
            }
        }
    }
    AbiosEnrichedDocument doc = new AbiosEnrichedDocument();
    doc.setSeries(series);
    logParsedSeriesShape(doc);           // shape log still works unchanged
    return doc;
}

public <T> List<T> parseAtlasListPayloadStreaming(
        InputStream in, String pathForLog, Class<T> elementType) throws IOException {
    List<T> results = new ArrayList<>();
    try (JsonParser parser = objectMapper.getFactory().createParser(in)) {
        JsonToken first = parser.nextToken();
        JsonToken arrayStart = first;
        if (first != JsonToken.START_ARRAY) {
            // find "data" array inside envelope
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME
                        && "data".equals(parser.currentName())) {
                    arrayStart = parser.nextToken();
                    break;
                }
            }
        }
        if (arrayStart != JsonToken.START_ARRAY) {
            log.warn("Abios batch response from {} has no array", pathForLog);
            return List.of();
        }
        MappingIterator<T> it = objectMapper.readValues(parser, elementType);
        while (it.hasNextValue()) results.add(it.nextValue());
    }
    return results;
}
```

> Custom Jackson deserializers (`CoerceToStringDeserializer`, `MaybeArrayOfPlayersDeserializer`,
> `PlayerRoleDeserializer`) are registered on the shared `ObjectMapper` and work transparently —
> `objectMapper.readValues()` honours all registered modules.

---

## Change 3 — Raw payload logging (`LOG_UPSTREAM_PAYLOAD`)

With streaming the body is consumed during parsing, so it cannot be logged after the fact.

**Option A (recommended):** drop raw body logging, keep the shape log.
`logParsedSeriesShape` already emits series count, participant slots, and player counts at INFO —
that covers the diagnostic use-case without buffering.

**Option B:** tee the stream when logging is explicitly enabled.
```java
InputStream effective = abios.isLogUpstreamPayload()
    ? new org.apache.commons.io.input.TeeInputStream(inputStream, loggingOutputStream)
    : inputStream;
```
This only buffers when `LOG_UPSTREAM_PAYLOAD=true`, so normal runs pay no extra cost.
Add `commons-io` to `pom.xml` if you go this route.

---

## Change 4 — `fetchBatch` call-site signature

`parseAtlasListPayloadStreaming` takes `Class<T>` instead of `TypeReference<List<T>>`:

```java
// Before
return responseParser.parseAtlasListPayload(payload, path, new TypeReference<List<AbiosRosterNode>>() {});

// After
return responseParser.parseAtlasListPayloadStreaming(inputStream, path, AbiosRosterNode.class);
```

Update all four call sites in `fetchRosters`, `fetchTeams`, `fetchPlayers`, and `fetchLineups`.

---

## Result

| | Before | After |
|---|---|---|
| Heap during parse | ~3× payload | ~1× payload (POJO list only) |
| `JsonNode` tree allocated | Yes | No |
| Intermediate `byte[]` | Yes | No |
| Custom deserializers | Work | Still work (same `ObjectMapper`) |
| Raw body logging | Always possible | Shape log always on; raw only with tee (opt-in) |
| Mock / test paths | Unchanged | Unchanged (old methods kept) |

`MappingIterator` processes one `AbiosSeriesNode` at a time and the GC can collect parser token
state as it goes — heap stays roughly proportional to the largest single object, not the full list.
