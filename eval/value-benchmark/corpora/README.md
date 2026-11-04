# Value-benchmark corpora (SYNTHETIC)

Every message here is synthetic. All people, clinics, streets, shops, brands and venues are fictional
(N-47). No real phone numbers, e-mail addresses, IDs or URLs. The only phone-shaped string,
`050-0000-417`, is an obviously fake canary inside a deleted message (building-he, h29). It is declared in the
gold `forbidden_strings` so the synthetic-data gate allowlists it.

One JSONL file per corpus. Each line is
`{id, author, sent_at, reply_to, forwarded, edited, text, deleted_at?}`.
- `sent_at` and `deleted_at` are ISO-8601 UTC.
- `deleted_at - sent_at <= 5 min` is a Track E deletion. Anything later is Track L (DEC-012).
- `edited: true` means `text` is the final edited text. Corpora place no edit after any question's `as_of`.

| corpus_id | lang | messages | decoy sibling (never a query scope) |
|---|---|---|---|
| parents-b | mixed (EN-heavy) | 25 | parents-b-decoy (4) |
| building-he | Hebrew-heavy | 42 | building-he-decoy (7) |
| family-en | English-heavy | 42 | family-en-decoy (6) |
| club-mixed | mixed HE/EN | 42 | club-mixed-decoy (6) |

`parents-b` has 25 messages, which is below the 40-message floor DESIGN §2 sets for the small regime. It was
migrated as-is to keep its labels comparable with `eval/questions-parents-b.jsonl`.

## parents-b migration (from `dita-core/src/test/resources/fixtures/parents-b.synthetic.txt`)

The original files were left untouched. Timestamps are `2026-09-01T08:00:00Z + minutesFromStart`. Entities
were renamed so that no real-sounding doctor, street or insurer combination remains:

| original | migrated |
|---|---|
| Dr. Katz / ד"ר כץ | Dr. Quillby / ד"ר קווילבי |
| Dr. Peretz | Dr. Puddlefoot / פאדלפוט |
| Weizmann St. / ויצמן | Lanternfish St. / דג הפנס |
| Maccabi / מכבי | Tumbleweed Health / טאמבלוויד |
| Dr. Levi (decoy) | Dr. Grumbleton |
| Herzl (decoy) | Marzipan Rd. |
