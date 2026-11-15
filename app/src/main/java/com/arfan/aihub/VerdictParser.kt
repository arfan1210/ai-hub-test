package com.arfan.aihub

/** Normalised reviewer verdict. FAIL is folded into REVISE by the loop. */
enum class Verdict { PASS, REVISE, FAIL }

/**
 * Result of parsing one reviewer reply.
 *
 * @param verdict   the verdict the reviewer actually wrote
 * @param issues    the ISSUES block, trimmed; empty when the reviewer said NONE
 * @param raw       the reply text the verdict came from (capped, for the log)
 */
data class ParsedVerdict(
    val verdict: Verdict,
    val issues: String,
    val raw: String
) {
    /** What the loop acts on: FAIL and REVISE both mean "needs another round". */
    val effective: Verdict get() = if (verdict == Verdict.FAIL) Verdict.REVISE else verdict

    val isPass: Boolean get() = effective == Verdict.PASS
}

/**
 * Strict parser for the reviewer verdict protocol.
 *
 * The reply handed in here must already have been captured from an
 * assistant-only DOM node (see [SiteAdapters]); this parser additionally
 * refuses self-referential text, so a page that only contains our own prompt
 * can never be mistaken for a verdict.
 *
 * It never invents a verdict: when no `VERDICT:` token is present the result is
 * `null`, which the callers surface as `CALIBRATION NEEDED`.
 */
object VerdictParser {

    private val VERDICT_RE =
        Regex("""VERDICT\s*[:\-]?\s*(PASS|REVISE|FAIL)\b""", RegexOption.IGNORE_CASE)

    private val ISSUES_RE = Regex("""ISSUES?\s*[:\-]""", RegexOption.IGNORE_CASE)

    /** Our own prompt text — if this shows up, we captured the wrong node. */
    private const val PROMPT_MARKER = "You are a strict independent reviewer"

    /** Reply text kept in memory/logs; plenty for the ISSUES block. */
    private const val MAX_RAW = 4000

    /** Longest ISSUES payload merged back into Qwen. */
    private const val MAX_ISSUES = 3000

    /**
     * Parses [reply]. The LAST `VERDICT:` occurrence wins, because reviewers
     * often restate an earlier draft before answering.
     */
    fun parse(reply: String?): ParsedVerdict? {
        val text = reply?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.contains(PROMPT_MARKER)) return null // our own prompt, not a reply

        val matches = VERDICT_RE.findAll(text).toList()
        if (matches.isEmpty()) return null

        val match = matches.last()
        val verdict = when (match.groupValues[1].uppercase()) {
            "PASS" -> Verdict.PASS
            "FAIL" -> Verdict.FAIL
            else -> Verdict.REVISE
        }

        val issues = extractIssues(text, match.range.last + 1)
            ?: extractIssuesBefore(text, match.range.first)

        return ParsedVerdict(
            verdict = verdict,
            issues = if (verdict == Verdict.PASS) issues else issues.ifBlank { "(no issues listed)" },
            raw = text.take(MAX_RAW)
        )
    }

    /** True when the reply is a well-formed verdict (used for stability checks). */
    fun hasVerdict(reply: String?): Boolean = parse(reply) != null

    /** Collects the ISSUES block that follows the verdict. */
    private fun extractIssues(text: String, from: Int): String? {
        if (from >= text.length) return null
        val tail = text.substring(from)
        val m = ISSUES_RE.find(tail) ?: return null
        var body = tail.substring(m.range.last + 1).trim()
        // Drop a duplicated trailing verdict line, if the reviewer wrote one.
        body = body.replace(VERDICT_RE, "").trim()
        return clean(body)
    }

    /** Fallback for reviewers that print ISSUES before the VERDICT line. */
    private fun extractIssuesBefore(text: String, before: Int): String? {
        val head = text.substring(0, before.coerceAtMost(text.length))
        val m = ISSUES_RE.find(head) ?: return null
        return clean(head.substring(m.range.last + 1).trim())
    }

    private fun clean(body: String): String? {
        val cleaned = body
            .replace(Regex("""^\s*[-*•]\s*""", RegexOption.MULTILINE), "- ")
            .trim()
        if (cleaned.isEmpty()) return null
        val upper = cleaned.uppercase()
        if (upper == "NONE" || upper == "N/A" || upper == "NO ISSUES" ||
            upper == "- NONE" || cleaned == "—"
        ) {
            return ""
        }
        return cleaned.take(MAX_ISSUES)
    }
}
