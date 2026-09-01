package com.saveory.frontwidget.proton.calendar

import android.util.Log
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.SessionKey
import me.proton.core.key.domain.decryptData
import me.proton.core.key.domain.useKeys
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.domain.ApiManager
import me.proton.core.network.domain.ApiResult
import me.proton.core.user.domain.UserManager
import me.proton.core.user.domain.entity.UserAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Fetches and decrypts upcoming Proton Calendar events for the primary account.
 *
 * Flow per calendar:
 *  1. bootstrap -> calendar keys + member passphrase (PGP-encrypted to a member address key).
 *  2. decrypt the member passphrase with the matching [UserAddress] key ring.
 *  3. unlock the calendar private key with that passphrase.
 *  4. list events across all query buckets (0..3) so recurring/ongoing series that STARTED before
 *     the window are included, then decrypt the encrypted parts (SUMMARY + recurrence rules).
 *  5. expand recurring masters (RRULE) into their in-window occurrences, applying EXDATE exclusions
 *     and RECURRENCE-ID overrides.
 *
 * Times are always available (cleartext); the title/recurrence are best-effort so a decryption edge
 * case never hides an event entirely.
 */
class ProtonCalendarRepository(
    private val accountManager: AccountManager,
    private val userManager: UserManager,
    private val apiProvider: ApiProvider,
    private val cryptoContext: CryptoContext
) {
    private val tag = "ProtonCalendar"

    private companion object {
        // CalendarEventsQueryType buckets. We query ALL of them so we don't miss:
        //   0/2 = part/full-day events STARTING inside the window
        //   1/3 = part/full-day events that STARTED BEFORE the window (still ongoing OR recurring
        //         series whose first occurrence predates the window - e.g. a biweekly "Pay Day").
        val ALL_EVENT_TYPES = intArrayOf(0, 1, 2, 3)
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 40
        // Don't split a windowed query below this span when Proton rejects it as too big.
        const val MIN_WINDOW_SPAN_SEC = 7L * 24 * 3600
        // Safety cap on recurrence iterations so a malformed/unbounded rule can't spin forever.
        const val MAX_OCCURRENCE_ITERATIONS = 5000
        // Clamp RRULE INTERVAL so iteration*interval math can't overflow Int (worst case
        // MAX_OCCURRENCE_ITERATIONS * MAX_INTERVAL * 7 stays well within Int range).
        const val MAX_INTERVAL = 1000
        // Cap BYDAY/BYMONTHDAY token counts so a giant list can't multiply per-iteration work.
        const val MAX_BYPARTS = 366
    }

    /** A decrypted master/override event with the recurrence metadata needed to expand it. */
    private data class ParsedEvent(
        val calId: String,
        val eventId: String,
        val uid: String,
        val title: String,
        val startSec: Long,
        val endSec: Long,
        val fullDay: Boolean,
        val tzId: String?,
        val rrule: String?,
        val exDatesSec: List<Long>,
        val recurrenceIdSec: Long?
    )

    suspend fun getUpcomingEvents(windowStartMs: Long, windowEndMs: Long): List<ProtonEvent> {
        val userId = accountManager.getPrimaryUserId().firstOrNull()
        if (userId == null) {
            Log.d(tag, "No primary Proton user; skipping.")
            return emptyList()
        }

        val addresses = runCatching { userManager.getAddresses(userId) }.getOrNull().orEmpty()
        if (addresses.isEmpty()) {
            Log.w(tag, "No addresses for user; cannot decrypt calendar keys.")
            return emptyList()
        }

        @Suppress("UNCHECKED_CAST")
        val api = apiProvider.get<CalendarApi>(userId) as ApiManager<CalendarApi>

        val calendarsJson = call(api, "getCalendars") { getCalendars() } ?: return emptyList()
        val calendarIds = calendarsJson.array("Calendars")
            .mapNotNull { it.jsonObject.str("ID") }
        Log.d(tag, "Found ${calendarIds.size} calendars")

        val tz = TimeZone.getDefault().id
        val windowStartSec = windowStartMs / 1000
        val windowEndSec = windowEndMs / 1000

        val parsed = mutableListOf<ParsedEvent>()
        for (calId in calendarIds) {
            try {
                val unlockedKey = unlockCalendarKey(api, calId, addresses) ?: continue

                // The windowed query (all buckets 0..3) is recurrence-aware server-side: it returns
                // in-window singles AND the MASTER of any series (recurring or multi-day) that
                // overlaps the window even when that master STARTS before it - e.g. a biweekly
                // "Pay Day" anchored months ago. We then decrypt (including the signed cleartext part
                // that carries RRULE/DTSTART) and expand recurrences client-side. This is both correct
                // and fast; the old full-listing scan was redundant and timed out on slow networks.
                val rawById = LinkedHashMap<String, JsonObject>()
                addRawEvents(rawById, fetchWindowed(api, calId, windowStartSec, windowEndSec, tz))

                Log.d(tag, "cal=${calId.take(8)} windowed=${rawById.size}")
                for (o in rawById.values) {
                    parseEvent(calId, o, unlockedKey)?.let { parsed += it }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed calendar $calId", e)
            }
        }

        val occurrences = buildOccurrences(parsed, windowStartSec, windowEndSec)
        Log.d(tag, "Decrypted ${parsed.size} raw events -> ${occurrences.size} in-window occurrences")

        // Keep only occurrences that actually overlap the window, de-duplicated by series+start.
        return occurrences
            .distinctBy { "${it.uid}@${it.startTime}" }
            .filter { it.startTime <= windowEndMs && it.endTime >= windowStartMs }
            .sortedBy { it.startTime }
    }

    /**
     * Turns the parsed masters + overrides into concrete [ProtonEvent] occurrences:
     *  - non-recurring master -> one occurrence at its cleartext start/end.
     *  - recurring master (RRULE) -> every occurrence inside [startSec, endSec], minus EXDATEs and
     *    minus any slot replaced by a RECURRENCE-ID override.
     *  - overrides -> added at their own (possibly moved) start/end.
     */
    private fun buildOccurrences(
        parsed: List<ParsedEvent>,
        windowStartSec: Long,
        windowEndSec: Long
    ): List<ProtonEvent> {
        val masters = parsed.filter { it.recurrenceIdSec == null }.distinctBy { it.eventId }
        val overrides = parsed.filter { it.recurrenceIdSec != null }.distinctBy { it.eventId }
        // A slot (series UID + original occurrence start) that an override replaces.
        val replacedSlots = overrides.mapNotNull { o -> o.recurrenceIdSec?.let { o.uid to it } }.toSet()

        val out = mutableListOf<ProtonEvent>()

        for (m in masters) {
            val rec = m.rrule?.let { parseRrule(it, m.tzId) }
            if (m.rrule == null || rec == null) {
                // Single (or unparseable-rule) event: emit once at its cleartext time.
                out += m.toProtonEvent(m.startSec, m.endSec)
                continue
            }
            val tz = TimeZone.getTimeZone(m.tzId ?: TimeZone.getDefault().id)
            val durationSec = (m.endSec - m.startSec).coerceAtLeast(0)
            val exSet = m.exDatesSec.toSet()
            val occ = expandOccurrences(m.startSec, durationSec, tz, rec, windowStartSec, windowEndSec, exSet)
            for ((oStart, oEnd) in occ) {
                if ((m.uid to oStart) in replacedSlots) continue // override provides this slot instead
                out += m.toProtonEvent(oStart, oEnd)
            }
        }

        // Overrides render as their own occurrence (moved time / edited title).
        for (o in overrides) {
            out += o.toProtonEvent(o.startSec, o.endSec)
        }

        return out
    }

    private fun ParsedEvent.toProtonEvent(startSec: Long, endSec: Long) = ProtonEvent(
        calendarId = calId,
        eventId = eventId,
        uid = uid,
        title = title,
        startTime = startSec * 1000,
        endTime = endSec * 1000,
        fullDay = fullDay
    )

    /**
     * Pages through the given query [types] over [startSec, endSec]. Returns the collected raw events
     * plus a flag indicating Proton rejected the request because the window span is too big (code
     * 2000), so callers can retry with a smaller window instead of silently getting nothing.
     */
    private suspend fun fetchEventsRange(
        api: ApiManager<CalendarApi>,
        calId: String,
        startSec: Long,
        endSec: Long,
        tz: String,
        types: IntArray
    ): Pair<List<JsonElement>, Boolean> {
        val all = mutableListOf<JsonElement>()
        for (type in types) {
            var page = 0
            pages@ while (page < MAX_PAGES) {
                when (val r = api.invoke { getEvents(calId, startSec, endSec, tz, type, page, PAGE_SIZE) }) {
                    is ApiResult.Success -> {
                        val events = r.value.array("Events")
                        all += events
                        if (events.size < PAGE_SIZE) break@pages
                        page++
                    }
                    is ApiResult.Error -> {
                        if (isWindowTooBig(r)) return all to true
                        Log.e(tag, "getEvents(type=$type) error: $r")
                        break@pages
                    }
                }
            }
        }
        return all to false
    }

    /**
     * Windowed query across all buckets, halving the range whenever Proton rejects it as "too big"
     * (there's a server-side cap on the span). Results across sub-windows overlap for recurring
     * masters but callers de-duplicate by event ID.
     */
    private suspend fun fetchWindowed(
        api: ApiManager<CalendarApi>,
        calId: String,
        startSec: Long,
        endSec: Long,
        tz: String
    ): List<JsonElement> {
        val (events, tooBig) = fetchEventsRange(api, calId, startSec, endSec, tz, ALL_EVENT_TYPES)
        if (!tooBig || endSec - startSec <= MIN_WINDOW_SPAN_SEC) return events
        val mid = startSec + (endSec - startSec) / 2
        return fetchWindowed(api, calId, startSec, mid, tz) +
            fetchWindowed(api, calId, mid, endSec, tz)
    }

    private fun isWindowTooBig(error: ApiResult.Error): Boolean =
        error.toString().contains("window is too big", ignoreCase = true)

    /** Adds raw events into [target] keyed by event ID, keeping the first seen (so we decrypt once). */
    private fun addRawEvents(target: LinkedHashMap<String, JsonObject>, events: List<JsonElement>) {
        for (e in events) {
            val obj = e.jsonObject
            val id = obj.str("ID") ?: continue
            target.putIfAbsent(id, obj)
        }
    }

    /** Decrypts the member passphrase and unlocks the primary calendar private key. */
    private suspend fun unlockCalendarKey(
        api: ApiManager<CalendarApi>,
        calId: String,
        addresses: List<UserAddress>
    ): ByteArray? {
        val bootstrap = call(api, "getBootstrap") { getBootstrap(calId) } ?: return null

        val keys = bootstrap.array("Keys")
        val passphraseObj = bootstrap["Passphrase"]?.jsonObject
        val members = bootstrap.array("Members")
        if (keys.isEmpty() || passphraseObj == null) {
            Log.w(tag, "Bootstrap missing keys/passphrase for $calId")
            return null
        }

        // Match a calendar member to one of our addresses (by AddressID, fallback Email).
        val memberByAddress = members.map { it.jsonObject }
        val myAddressIds = addresses.map { it.addressId.id }.toSet()
        val myEmails = addresses.map { it.email.lowercase() }.toSet()
        val member = memberByAddress.firstOrNull { m ->
            m.str("AddressID") in myAddressIds || m.str("Email")?.lowercase() in myEmails
        } ?: memberByAddress.firstOrNull()
        val memberId = member?.str("ID")

        // Find this member's encrypted passphrase blob.
        val memberPassphrases = passphraseObj.array("MemberPassphrases")
        val myPassphrase = memberPassphrases.map { it.jsonObject }.firstOrNull {
            it.str("MemberID") == memberId
        } ?: memberPassphrases.map { it.jsonObject }.firstOrNull()
        val armoredPassphrase = myPassphrase?.str("Passphrase")
        if (armoredPassphrase == null) {
            Log.w(tag, "No member passphrase for $calId")
            return null
        }

        // Pick the address that owns this member (or the first) and decrypt with its keys.
        val address = addresses.firstOrNull { it.addressId.id == member?.str("AddressID") }
            ?: addresses.first()
        val passphraseBytes = try {
            address.useKeys(cryptoContext) { decryptData(armoredPassphrase) }
        } catch (e: Exception) {
            Log.e(tag, "Passphrase decrypt failed for $calId", e)
            return null
        }

        // Primary calendar private key (Flags bit 1 = primary in Proton's model; fallback first).
        val primaryKey = keys.map { it.jsonObject }.firstOrNull { (it.long("Flags") ?: 0L) and 1L == 1L }
            ?: keys.first().jsonObject
        val armoredPrivateKey = primaryKey.str("PrivateKey") ?: return null

        return try {
            cryptoContext.pgpCrypto.unlock(armoredPrivateKey, passphraseBytes).value
        } catch (e: Exception) {
            Log.e(tag, "Calendar key unlock failed for $calId", e)
            null
        }
    }

    private fun parseEvent(calId: String, ev: JsonObject, unlockedCalKey: ByteArray): ParsedEvent? {
        val eventId = ev.str("ID") ?: return null
        val uid = ev.str("UID") ?: eventId
        val startSec = ev.long("StartTime") ?: return null
        val endSec = ev.long("EndTime") ?: startSec
        val fullDay = (ev.long("FullDay") ?: 0L) == 1L
        // Cleartext timezone hints if present (Proton usually includes these alongside StartTime).
        val cleartextTz = ev.str("StartTimezone")

        val ics = decryptIcs(ev, unlockedCalKey)

        var title = ics?.let { parseSummary(it) }
        if (title.isNullOrBlank()) title = "(Private event)"

        val rrule = ics?.let { icsProp(it, "RRULE") }
        val icsTz = ics?.let { icsParam(it, "DTSTART", "TZID") }
        val tzId = cleartextTz ?: icsTz
        val exDates = ics?.let { parseExDates(it) }.orEmpty()
        val recurrenceIdSec = ics?.let { rid ->
            icsProp(rid, "RECURRENCE-ID")?.let { parseIcsDateToSec(it, icsParam(rid, "RECURRENCE-ID", "TZID") ?: tzId) }
        }

        return ParsedEvent(
            calId = calId,
            eventId = eventId,
            uid = uid,
            title = title,
            startSec = startSec,
            endSec = endSec,
            fullDay = fullDay,
            tzId = tzId,
            rrule = rrule,
            exDatesSec = exDates,
            recurrenceIdSec = recurrenceIdSec
        )
    }

    /**
     * Recovers the event's ICS by concatenating every SharedEvents part. Proton stores an event's
     * calendar data across several parts with different protection:
     *  - ENCRYPTED (SUMMARY/description/location) -> decrypt with the shared session key.
     *  - SIGNED-only cleartext (DTSTART/RRULE/EXDATE/RECURRENCE-ID/UID) -> the ICS is in the clear
     *    inside a PGP signed-message wrapper; we must read it, NOT try to decrypt it.
     * Keeping only decryptable parts (the old behavior) silently dropped the RRULE, which lives in
     * the signed part - so recurring series like a biweekly "Pay Day" never expanded.
     */
    private fun decryptIcs(ev: JsonObject, unlockedCalKey: ByteArray): String? {
        val sharedEvents = ev.array("SharedEvents").map { it.jsonObject }
        if (sharedEvents.isEmpty()) return null

        val sessionKey: SessionKey? = ev.str("SharedKeyPacket")?.let { packet ->
            try {
                cryptoContext.pgpCrypto.decryptSessionKey(
                    cryptoContext.pgpCrypto.getBase64Decoded(packet), unlockedCalKey
                )
            } catch (e: Exception) {
                Log.e(tag, "Session key decrypt failed", e); null
            }
        }

        val sb = StringBuilder()
        for (part in sharedEvents) {
            val data = part.str("Data") ?: continue
            val text = extractPartText(data, sessionKey) ?: continue
            sb.append(text).append('\n')
        }
        return sb.toString().ifBlank { null }
    }

    /**
     * Returns the ICS text carried by a single SharedEvents [data] blob. Handles, in order:
     * a PGP signed-message wrapper (cleartext body), a bare cleartext ICS fragment, and finally an
     * encrypted blob (armored or base64 binary) decrypted with [sessionKey].
     */
    private fun extractPartText(data: String, sessionKey: SessionKey?): String? {
        val trimmed = data.trimStart()

        // 1) Cleartext-signed: ICS sits between the header's blank line and the signature block.
        if (trimmed.startsWith("-----BEGIN PGP SIGNED MESSAGE-----")) {
            val body = trimmed.substringAfter("\n\n", "")
                .substringBefore("-----BEGIN PGP SIGNATURE-----")
                .trimEnd()
            return body.ifBlank { null }
        }

        // 2) Bare cleartext ICS fragment (no PGP wrapper at all).
        if (trimmed.startsWith("BEGIN:") ||
            Regex("(?m)^(SUMMARY|DTSTART|DTEND|RRULE|EXDATE|RECURRENCE-ID|UID|DURATION)[:;]")
                .containsMatchIn(trimmed)
        ) {
            return trimmed
        }

        // 3) Encrypted blob: armored PGP message or raw base64 binary, decrypted with the session key.
        if (sessionKey == null) return null
        return try {
            val bytes = if (trimmed.startsWith("-----BEGIN")) {
                cryptoContext.pgpCrypto.getBase64Decoded(
                    trimmed.substringAfter("\n\n").substringBefore("\n-----END").replace("\n", "")
                )
            } else {
                cryptoContext.pgpCrypto.getBase64Decoded(data)
            }
            cryptoContext.pgpCrypto.decryptData(bytes, sessionKey).decodeToString()
        } catch (e: Exception) {
            // Expected for binary attachments; the caller tries every part.
            null
        }
    }

    private fun parseSummary(ics: String): String? {
        // ICS: SUMMARY may be folded across lines and escaped.
        val regex = Regex("(?m)^SUMMARY(?:;[^:]*)?:(.*(?:\\r?\\n[ \\t].*)*)")
        val match = regex.find(ics) ?: return null
        return match.groupValues[1]
            .replace(Regex("\\r?\\n[ \\t]"), "")
            .replace("\\n", " ")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
            .trim()
            .ifBlank { null }
    }

    // ---- ICS field helpers ----

    /** Unfolds ICS content lines (RFC 5545: a CRLF followed by space/tab continues the prior line). */
    private fun icsUnfold(ics: String): List<String> =
        ics.replace(Regex("\\r?\\n[ \\t]"), "").split(Regex("\\r?\\n"))

    /** The value (after ':') of the first line whose property name matches [name] (params stripped). */
    private fun icsProp(ics: String, name: String): String? {
        for (line in icsUnfold(ics)) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val prop = line.substring(0, colon).substringBefore(';')
            if (prop.equals(name, ignoreCase = true)) return line.substring(colon + 1).trim()
        }
        return null
    }

    /** The value of parameter [param] on property [name] (e.g. DTSTART;TZID=America/New_York:...). */
    private fun icsParam(ics: String, name: String, param: String): String? {
        for (line in icsUnfold(ics)) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val head = line.substring(0, colon)
            if (!head.substringBefore(';').equals(name, ignoreCase = true)) continue
            head.substringAfter(';', "").split(';').forEach { seg ->
                val kv = seg.split('=', limit = 2)
                if (kv.size == 2 && kv[0].equals(param, ignoreCase = true)) return kv[1]
            }
        }
        return null
    }

    /** All EXDATE instants (there can be multiple EXDATE lines, each comma-separated), in epoch secs. */
    private fun parseExDates(ics: String): List<Long> {
        val out = mutableListOf<Long>()
        for (line in icsUnfold(ics)) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val head = line.substring(0, colon)
            if (!head.substringBefore(';').equals("EXDATE", ignoreCase = true)) continue
            val tzId = head.substringAfter(';', "").split(';')
                .firstOrNull { it.startsWith("TZID=", true) }?.substringAfter('=')
            line.substring(colon + 1).split(',').forEach { v ->
                parseIcsDateToSec(v, tzId)?.let { out += it }
            }
        }
        return out
    }

    /** Parses an ICS date/date-time (YYYYMMDD[THHMMSS[Z]]) to epoch seconds in the given timezone. */
    private fun parseIcsDateToSec(value: String, tzId: String?): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val isUtc = v.endsWith("Z")
        val core = v.removeSuffix("Z")
        val hasTime = core.contains("T")
        val pattern = if (hasTime) "yyyyMMdd'T'HHmmss" else "yyyyMMdd"
        val sdf = SimpleDateFormat(pattern, Locale.US)
        sdf.timeZone = if (isUtc) TimeZone.getTimeZone("UTC")
            else TimeZone.getTimeZone(tzId ?: TimeZone.getDefault().id)
        return runCatching { sdf.parse(core)?.time?.div(1000) }.getOrNull()
    }

    // ---- Recurrence (RRULE) parsing + expansion ----

    private data class ByDay(val nth: Int, val dow: Int) // nth 0 = "every"; dow = Calendar.SUNDAY..SATURDAY

    private data class Recurrence(
        val freq: String,
        val interval: Int,
        val count: Int?,
        val untilSec: Long?,
        val byDay: List<ByDay>,
        val byMonthDay: List<Int>
    )

    private fun parseRrule(rrule: String, tzId: String?): Recurrence? {
        val parts = rrule.split(';').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0].trim().uppercase() to kv[1].trim() else null
        }.toMap()
        val freq = parts["FREQ"]?.uppercase() ?: return null
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
        // coerceIn (not just coerceAtLeast): an attacker-supplied INTERVAL like 2_000_000_000 would
        // otherwise overflow Int in `iteration * interval` and defeat the window early-exit.
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceIn(1, MAX_INTERVAL) ?: 1
        val count = parts["COUNT"]?.toIntOrNull()
        val untilSec = parts["UNTIL"]?.let { parseIcsDateToSec(it, tzId) }
        val byDay = parts["BYDAY"]?.split(',')?.take(MAX_BYPARTS)?.mapNotNull { parseByDay(it) } ?: emptyList()
        val byMonthDay = parts["BYMONTHDAY"]?.split(',')?.take(MAX_BYPARTS)
            ?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
        return Recurrence(freq, interval, count, untilSec, byDay, byMonthDay)
    }

    private fun parseByDay(token: String): ByDay? {
        val m = Regex("^([+-]?\\d+)?(MO|TU|WE|TH|FR|SA|SU)$").find(token.trim().uppercase()) ?: return null
        val nth = m.groupValues[1].toIntOrNull() ?: 0
        val dow = when (m.groupValues[2]) {
            "SU" -> Calendar.SUNDAY; "MO" -> Calendar.MONDAY; "TU" -> Calendar.TUESDAY
            "WE" -> Calendar.WEDNESDAY; "TH" -> Calendar.THURSDAY; "FR" -> Calendar.FRIDAY
            "SA" -> Calendar.SATURDAY; else -> return null
        }
        return ByDay(nth, dow)
    }

    /** Monday=0 .. Sunday=6 offset for a Calendar day-of-week constant. */
    private fun isoOffset(dow: Int): Int = (dow + 5) % 7

    /**
     * Expands a recurring series into (startSec, endSec) occurrence pairs that fall inside
     * [windowStartSec, windowEndSec]. Iterates from the series start (so COUNT is honored), keeps the
     * anchor's local time-of-day, and stops at UNTIL / COUNT / window end / a hard iteration cap.
     */
    private fun expandOccurrences(
        anchorStartSec: Long,
        durationSec: Long,
        tz: TimeZone,
        rec: Recurrence,
        windowStartSec: Long,
        windowEndSec: Long,
        exDates: Set<Long>
    ): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        val untilSec = rec.untilSec ?: Long.MAX_VALUE
        val hardEnd = minOf(windowEndSec, untilSec)
        val maxCount = rec.count ?: Int.MAX_VALUE
        var counted = 0

        val anchor = Calendar.getInstance(tz).apply { timeInMillis = anchorStartSec * 1000 }
        val h = anchor.get(Calendar.HOUR_OF_DAY)
        val min = anchor.get(Calendar.MINUTE)
        val sec = anchor.get(Calendar.SECOND)

        fun applyTime(c: Calendar) {
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min)
            c.set(Calendar.SECOND, sec); c.set(Calendar.MILLISECOND, 0)
        }

        // Records an occurrence start; returns false when the series is exhausted (stop iterating).
        fun emit(startSec: Long): Boolean {
            if (startSec > hardEnd) return false
            if (startSec >= anchorStartSec) {
                counted++
                if (counted > maxCount) return false
                if (startSec in windowStartSec..hardEnd && startSec !in exDates) {
                    out += startSec to (startSec + durationSec)
                }
            }
            return true
        }

        when (rec.freq) {
            "DAILY" -> {
                var i = 0
                while (i < MAX_OCCURRENCE_ITERATIONS) {
                    val c = anchor.clone() as Calendar
                    c.add(Calendar.DAY_OF_YEAR, i * rec.interval)
                    if (!emit(c.timeInMillis / 1000)) break
                    i++
                }
            }
            "WEEKLY" -> {
                val days = if (rec.byDay.isNotEmpty()) rec.byDay.map { it.dow }
                    else listOf(anchor.get(Calendar.DAY_OF_WEEK))
                // Monday of the anchor's week; each iteration steps whole weeks by INTERVAL.
                val weekMonday = (anchor.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, -isoOffset(get(Calendar.DAY_OF_WEEK)))
                }
                var w = 0
                loop@ while (w < MAX_OCCURRENCE_ITERATIONS) {
                    val base = weekMonday.clone() as Calendar
                    base.add(Calendar.DAY_OF_YEAR, w * rec.interval * 7)
                    for (dow in days.sortedBy { isoOffset(it) }) {
                        val c = base.clone() as Calendar
                        c.add(Calendar.DAY_OF_YEAR, isoOffset(dow))
                        applyTime(c)
                        if (!emit(c.timeInMillis / 1000)) break@loop
                    }
                    // Stop once the whole week is past the end (the emit break above usually fires first).
                    if (base.timeInMillis / 1000 > hardEnd) break
                    w++
                }
            }
            "MONTHLY" -> {
                var m = 0
                loop@ while (m < MAX_OCCURRENCE_ITERATIONS) {
                    val base = (anchor.clone() as Calendar).apply {
                        set(Calendar.DAY_OF_MONTH, 1)
                        add(Calendar.MONTH, m * rec.interval)
                    }
                    val year = base.get(Calendar.YEAR)
                    val month = base.get(Calendar.MONTH)
                    val dim = base.getActualMaximum(Calendar.DAY_OF_MONTH)
                    val targetDays = when {
                        rec.byMonthDay.isNotEmpty() ->
                            rec.byMonthDay.mapNotNull { normMonthDay(it, dim) }
                        rec.byDay.isNotEmpty() ->
                            rec.byDay.flatMap { weekdayDaysOfMonth(year, month, it.dow, it.nth, tz) }
                        else -> listOf(minOf(anchor.get(Calendar.DAY_OF_MONTH), dim))
                    }.distinct().sorted()
                    for (dom in targetDays) {
                        val c = base.clone() as Calendar
                        c.set(Calendar.DAY_OF_MONTH, dom)
                        applyTime(c)
                        if (!emit(c.timeInMillis / 1000)) break@loop
                    }
                    if (base.timeInMillis / 1000 > hardEnd) break
                    m++
                }
            }
            "YEARLY" -> {
                var y = 0
                while (y < MAX_OCCURRENCE_ITERATIONS) {
                    val c = anchor.clone() as Calendar
                    c.add(Calendar.YEAR, y * rec.interval)
                    val s = c.timeInMillis / 1000
                    if (!emit(s)) break
                    if (s > hardEnd) break
                    y++
                }
            }
        }
        return out
    }

    /** Normalizes a BYMONTHDAY value (negative counts from month end) to a valid day, or null. */
    private fun normMonthDay(md: Int, daysInMonth: Int): Int? = when {
        md in 1..daysInMonth -> md
        md < 0 && (daysInMonth + md + 1) in 1..daysInMonth -> daysInMonth + md + 1
        else -> null
    }

    /**
     * Days-of-month matching a BYDAY entry: nth==0 -> every matching weekday; nth>0 -> the nth one;
     * nth<0 -> counted from the month end (e.g. -1 = last).
     */
    private fun weekdayDaysOfMonth(year: Int, month: Int, dow: Int, nth: Int, tz: TimeZone): List<Int> {
        val c = Calendar.getInstance(tz).apply { clear(); set(year, month, 1) }
        val dim = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        val matches = mutableListOf<Int>()
        for (d in 1..dim) {
            c.set(Calendar.DAY_OF_MONTH, d)
            if (c.get(Calendar.DAY_OF_WEEK) == dow) matches += d
        }
        return when {
            nth == 0 -> matches
            nth > 0 -> matches.getOrNull(nth - 1)?.let { listOf(it) } ?: emptyList()
            else -> matches.getOrNull(matches.size + nth)?.let { listOf(it) } ?: emptyList()
        }
    }

    // ---- JSON + API helpers ----

    private suspend fun <T> call(
        api: ApiManager<CalendarApi>,
        label: String,
        block: suspend CalendarApi.() -> T
    ): T? = when (val r = api.invoke(block)) {
        is ApiResult.Success -> r.value
        is ApiResult.Error -> { Log.e(tag, "$label API error: $r"); null }
    }

    private fun JsonObject.array(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.toList() ?: emptyList()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()
}
