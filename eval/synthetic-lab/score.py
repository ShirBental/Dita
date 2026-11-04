#!/usr/bin/env python3
"""TASK-044 Synthetic Lab scorer: applies the frozen rubric.json to Dita's answers (results/answers-<backend>.jsonl).
Deterministic checks only; 'refusal' items are listed for manual review, never auto-passed.
Usage: python3 -I eval/synthetic-lab/score.py <answers-tag> [lab|holdout]   (run from the project root)
holdout: scores eval/synthetic-holdout/results/answers-<tag>.jsonl against Peter's SEALED rubric (sha256 checked)."""
import hashlib, json, sys

RUBRICS = {"lab": ("eval/synthetic-lab/rubric.json", "eval/synthetic-lab/results",
                   "decbb0e0b131147659596d0100ab1a346316470c1e81cdf9695a3da18463ba2a"),
           "holdout": ("eval/synthetic-holdout/sealed/rubric.json", "eval/synthetic-holdout/results",
                       "691e796b203332247fdc0f460ff3798e6194c44570b5ab12599eb5e80e970884"),
           "holdout-v2": ("eval/synthetic-holdout-v2/sealed/rubric.json", "eval/synthetic-holdout-v2/results",
                          "8a4480e1dbbdfdc66cf61953f23990554493095b7640c84657f236fbf40f3cd2")}
backend = sys.argv[1] if len(sys.argv) > 1 else "in_memory"
dataset = sys.argv[2] if len(sys.argv) > 2 else "lab"
RUBRIC_PATH, RESULTS, FROZEN_RUBRIC_SHA256 = RUBRICS[dataset]
raw = open(RUBRIC_PATH, "rb").read()
if hashlib.sha256(raw).hexdigest() != FROZEN_RUBRIC_SHA256:
    sys.exit("rubric.json changed since it was frozen; refusing to score")
rubric = {q["q"]: q for q in json.loads(raw)["questions"]}
answers = {a["q"]: a for a in (json.loads(l) for l in open(f"{RESULTS}/answers-{backend}.jsonl") if l.strip())}
assert set(answers) == set(rubric), "answers must cover every rubric question"

def has(text, alts): return any(a.lower() in text.lower() for a in alts)

HEADERS = ("what the group said", "conflicting reports", "members' views differ", "members disagree", "(these are group members")
def content_lines(text):
    return [l for l in text.splitlines() if l.strip() and not l.strip().lower().startswith(HEADERS)]

rows, totals = [], {"user_facing": 0, "cite_ok": 0, "cite_n": 0, "facts": 0, "facts_n": 0, "sources": 0, "sources_n": 0, "temporal": 0, "temporal_n": 0,
                    "qualifier": 0, "qualifier_n": 0, "speaker": 0, "speaker_n": 0, "behavior": 0, "all_auto": 0}
for q, r in rubric.items():
    a = answers[q]; text = a["answer_text"]; cited = [c["message_id"] for c in a["citations"]]
    by_author = {}
    for c in a["citations"]: by_author.setdefault(c["author"], set()).add(c["message_id"])
    facts = all(has(text, g) for g in r["facts"]) if r["facts"] else None
    src = (all(i in cited for i in r["sources_all"]) and (not r["sources_any"] or any(i in cited for i in r["sources_any"]))) \
        if (r["sources_all"] or r["sources_any"]) else None
    stale = [i for i in r["superseded"] if i in cited]
    temporal = (not stale) if r["superseded"] else None
    qual = all(has(text, g) for g in r["qualifiers"]) if r["qualifiers"] else None
    spk = None
    if r.get("speaker"):
        spk = all(any(i in by_author.get(author, set()) for i in ids) for author, ids in r["speaker"].items())
    if r["expected"] == "ANSWER": beh = a["behavior"] == "ANSWER"
    else: beh = a["behavior"] in ("UNKNOWN", "ANSWER")          # hedged answers allowed; refusal checked manually
    checks = [x for x in (facts, src, temporal, qual, spk) if x is not None] + [beh]
    # User-facing (pre-registered 2026-10-30 before any knowledge-layer answer existed): the reader must get the answer
    # from the first two content lines, and nothing superseded may be shown.
    lead = "\n".join(content_lines(text)[:2])
    facts_lead = all(has(lead, g) for g in r["facts"]) if r["facts"] else None
    relevant = set(r["sources_all"]) | set(r["sources_any"]) | {i for ids in r.get("speaker", {}).values() for i in ids}
    if relevant and cited:
        totals["cite_n"] += len(cited); totals["cite_ok"] += sum(1 for i in cited if i in relevant)
    user_facing = all(checks) and facts_lead is not False and temporal is not False
    totals["user_facing"] += int(user_facing)
    for k, v in (("facts", facts), ("sources", src), ("temporal", temporal), ("qualifier", qual), ("speaker", spk)):
        if v is not None: totals[k + "_n"] += 1; totals[k] += int(v)
    totals["behavior"] += int(beh); totals["all_auto"] += int(all(checks))
    rows.append((q, r["question"], a["behavior"], facts, src, temporal, qual, spk, all(checks), user_facing, cited, stale, r["refusal"]))

def f(v): return "-" if v is None else ("yes" if v else "NO")
N = len(rubric)
print(f"# Synthetic {dataset} — Dita deterministic scoring ({backend})\n")
print("Frozen rubric sha256 " + FROZEN_RUBRIC_SHA256 + ". No model calls. 'all auto checks' = every applicable deterministic check passed; refusal items need manual review.\n")
print("| q | question | Dita | facts | sources | temporal | qualifier | speaker | all auto checks | user-facing correct | cited fixture ids | stale cited | manual refusal check |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for q, qs, beh, fa, sr, te, qu, sp, ok, uf, cited, stale, ref in rows:
    print(f"| {q} | {qs} | {beh} | {f(fa)} | {f(sr)} | {f(te)} | {f(qu)} | {f(sp)} | {f(ok)} | {f(uf)} | {' '.join(map(str, cited))} | {' '.join(map(str, stale))} | {ref or '-'} |")
t = totals
prec = (t["cite_ok"] / t["cite_n"]) if t["cite_n"] else float("nan")
print(f"\n**Totals:** user-facing correct {t['user_facing']}/{N} · citation precision {prec:.2f} ({t['cite_ok']}/{t['cite_n']}) · all auto checks {t['all_auto']}/{N} · facts {t['facts']}/{t['facts_n']} · sources {t['sources']}/{t['sources_n']} · "
      f"temporal {t['temporal']}/{t['temporal_n']} · qualifiers {t['qualifier']}/{t['qualifier_n']} · speaker attribution {t['speaker']}/{t['speaker_n']} · behaviour {t['behavior']}/{N}")
