package app.doorprints.shared.api

/**
 * Response bodies as the Spring Boot backend writes them (Jackson 3, record component order, Postgres microsecond
 * timestamps, nulls included), copied from the backend's DTOs and filters:
 *  - HouseDto / VisitDto / PhotoDto records (app.doorprints.server.house, .visit, .photo), incl. server-only fields
 *    such as distanceMeters that the app must ignore,
 *  - ApiKeyFilter / ApiRateLimitFilter / AiRateLimitFilter hand-written problem bodies,
 *  - ApiExceptionHandler / AiExceptionHandler RFC 7807 ProblemDetail bodies,
 *  - a typical captive-portal sign-in page (hotel/airport Wi-Fi) answering 200 text/html.
 */
object RecordedResponses {
    const val STATS = """{"houses":12,"shortlisted":3,"rejected":2,"visits":27,"streets":9}"""

    const val HOUSES_SINCE = """[
  {"id":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","label":"2BHK near Indiranagar metro","address":"12, 5th Cross, HAL 2nd Stage, Indiranagar, Bengaluru","street":"5th Cross","locality":"Indiranagar","lat":12.978321,"lon":77.640812,"status":"SHORTLISTED","price":32000,"priceType":"RENT","bedrooms":2,"rating":4,"contactName":null,"contactPhone":null,"listingUrl":"https://example.com/listing/123","notes":"Water 24x7, lift, 1 covered parking","checklist":{"water":5,"parking":4,"noise":2},"createdAt":"2026-09-20T08:30:12.345678Z","updatedAt":"2026-09-21T17:02:44.901234Z","deleted":false,"syncVersion":41,"distanceMeters":null},
  {"id":"9e7c2a44-1b3f-4f0e-8a77-2c5d9e0f1a22","label":"Old villa","address":null,"street":null,"locality":null,"lat":12.9352,"lon":77.6245,"status":"ARCHIVED","price":null,"priceType":null,"bedrooms":null,"rating":null,"contactName":null,"contactPhone":null,"listingUrl":null,"notes":null,"checklist":{},"createdAt":"2026-09-01T10:00:00Z","updatedAt":"2026-09-22T06:15:00Z","deleted":true,"syncVersion":42,"distanceMeters":null}
]"""

    const val VISITS_SINCE = """[{"id":"0c6f5a2b-7d4e-4b8a-9c1d-3e2f1a0b9c88","houseId":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","lat":12.97829,"lon":77.64079,"street":"5th Cross","arrivedAt":"2026-09-21T11:05:00.250Z","leftAt":"2026-09-21T11:31:42.750Z","source":"AUTO","updatedAt":"2026-09-21T11:31:43.001122Z","deleted":false,"syncVersion":17}]"""

    const val PHOTOS_SINCE = """[
  {"id":"a1b2c3d4-0000-4000-8000-000000000001","houseId":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","contentType":"image/jpeg","sizeBytes":184233,"createdAt":"2026-09-21T11:20:00.123456Z","updatedAt":"2026-09-21T11:20:00.123456Z","deleted":false,"syncVersion":7},
  {"id":"a1b2c3d4-0000-4000-8000-000000000002","houseId":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","contentType":"image/jpeg","sizeBytes":0,"createdAt":"2026-09-21T11:21:00Z","updatedAt":"2026-09-22T07:00:00Z","deleted":true,"syncVersion":8}
]"""

    /** PUT echo: the server stamps syncVersion and its own updatedAt precision. */
    const val HOUSE_PUT_ECHO = """{"id":"h-1","label":"Flat","address":null,"street":"MG Road","locality":null,"lat":12.97,"lon":77.59,"status":"NEW","price":null,"priceType":"RENT","bedrooms":null,"rating":null,"contactName":null,"contactPhone":null,"listingUrl":null,"notes":null,"checklist":{"water":3},"createdAt":"2026-09-22T10:15:30Z","updatedAt":"2026-09-22T10:15:30.120Z","deleted":false,"syncVersion":43,"distanceMeters":null}"""

    const val PHOTO_UPLOADED = """{"id":"p-1","houseId":"h-1","contentType":"image/jpeg","sizeBytes":5,"createdAt":"2026-09-22T10:15:31.000001Z","updatedAt":"2026-09-22T10:15:31.000001Z","deleted":false,"syncVersion":9}"""

    const val AI_STATUS_OFF = """{"enabled":false,"mcpEnabled":false,"chatModel":null,"embeddingModel":null}"""

    const val ASK_RESPONSE = """{"answer":"The Indiranagar 2BHK has 24x7 water [5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10].","citations":[{"houseId":"5b1f3c1e-8d0a-4c55-9a51-0d2a6f7e9b10","label":"2BHK near Indiranagar metro","snippet":"Water 24x7, lift"}],"grounded":true,"retrieved":3,"latencyMs":812}"""

    // ---- error bodies ----

    /** ApiKeyFilter: missing or wrong key. */
    const val UNAUTHORIZED = """{"status":401,"detail":"Missing or wrong X-API-Key"}"""

    /** ApiKeyFilter: too many failed keys from this client (sent with Retry-After). */
    const val AUTH_LOCKOUT = """{"status":429,"detail":"Too many failed attempts, retry in 300s"}"""

    /** ApiRateLimitFilter (sent with Retry-After). */
    const val RATE_LIMITED = """{"status":429,"detail":"Rate limit exceeded, retry in 2s"}"""

    /** AiExceptionHandler: provider down / quota exhausted (ProblemDetail, sent with Retry-After: 60 for quota). */
    const val AI_UNAVAILABLE = """{"type":"about:blank","title":"Service Unavailable","status":503,"detail":"AI provider error. The AI provider's quota or rate limit is exhausted; try again later.","instance":"/api/ai/ask","retryable":true,"code":"AI_QUOTA_EXHAUSTED"}"""

    /** ApiExceptionHandler.notFound. */
    const val NOT_FOUND = """{"type":"about:blank","title":"Not Found","status":404,"detail":"Photo not found","instance":"/api/photos/p-9"}"""

    /** ApiExceptionHandler.conflict. */
    const val CONFLICT = """{"type":"about:blank","title":"Conflict","status":409,"detail":"Photo limit reached for this house (20)","instance":"/api/houses/h-1/photos"}"""

    /** ApiExceptionHandler.invalid (bean validation). */
    const val BAD_REQUEST = """{"type":"about:blank","title":"Bad Request","status":400,"detail":"rating: must be less than or equal to 5","instance":"/api/houses/h-1"}"""

    /** RequestSizeLimitFilter. */
    const val TOO_LARGE = """{"status":413,"detail":"Request body too large (max 6291456 bytes)"}"""

    /** What a free host's proxy returns while the app is waking up. */
    const val BAD_GATEWAY_HTML = "<html><head><title>502 Bad Gateway</title></head><body><h1>502 Bad Gateway</h1></body></html>"

    /** A hotel Wi-Fi sign-in page served with 200 instead of the API's JSON. */
    const val CAPTIVE_PORTAL_HTML = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Welcome to Hotel Guest Wi-Fi</title>
<meta http-equiv="refresh" content="0; url=http://10.10.0.1/login?dst=https%3A%2F%2Fapi.example.com%2Fapi%2Fstats"></head>
<body><form method="post" action="http://10.10.0.1/login"><input name="room"><input name="surname" type="text">
<button type="submit">Connect</button></form></body></html>"""
}
