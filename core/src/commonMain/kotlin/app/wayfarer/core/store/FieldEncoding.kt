package app.wayfarer.core.store

/**
 * Makes free text safe to carry in one field of one record.
 *
 * Both permission directories persist as line-oriented, tab-separated text, and
 * both carry exactly one field that is not an enum name, a number or a validated
 * identifier: the *reason* a host or relay ended up in the queue. That field
 * exists to be read by a person deciding whether to allow something, so it is
 * written to be descriptive — and the most descriptive thing available is a
 * name taken from a profile, which is written by whoever the user is reading.
 *
 * A tab in it would invent a field. A newline would end the record and let the
 * rest of the string parse as a line of the attacker's choosing — including a
 * grant line, which is an *approval*. That turns "text shown next to a decision"
 * into "the decision", and it is the whole consent model, so the escaping lives
 * here rather than in each codec: a third directory must not have to rediscover
 * the rule.
 *
 * Separators become spaces rather than being dropped, so the text stays readable
 * and its length is unchanged — a name written "a\nb" reads as "a b" rather than
 * silently becoming "ab".
 */
internal fun sanitizeField(text: String?): String =
    text.orEmpty().map { if (it == '\t' || it == '\n' || it == '\r') ' ' else it }.joinToString("")
