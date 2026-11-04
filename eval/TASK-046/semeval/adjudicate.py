"""TASK-046 adjudication (PREREGISTRATION.md section 7). Run: python3 -I eval/TASK-046/semeval/adjudicate.py <set>

Inputs:  <set>/cases-*.json (authorGold), <set>/labels/labels-<annotator>-*.json (blind annotators).
Outputs: <set>/gold-adjudicated.json, <set>/agreement.md (Cohen's kappa + PABAK per field, disagreement list for operator's audit).
Rules (fixed before any arm result is looked at):
  pairs        majority of {author, annotators} per (later, earlier) for sameProperty and replaces; no majority -> uncertain
  status       majority per item; no majority -> the WEAKEST proposed label (conservative)
  current      majority per item
  answer       a phrase is kept if >= 2 labellers list it (case-insensitive containment either way)
  invalid      >= 2 labellers mark it invalid
"""
import glob, json, os, sys
from collections import Counter

SET = sys.argv[1] if len(sys.argv) > 1 else "dev"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), SET)
STRENGTH = {"DECISION": 3, "CONFIRMED_COMMITMENT": 2, "OFFER": 1}

cases = []
for f in sorted(glob.glob(os.path.join(ROOT, "cases-*.json"))):
    cases += json.load(open(f))
labels = {}
for f in sorted(glob.glob(os.path.join(ROOT, "labels", "labels-*.json"))):
    who = os.path.basename(f).split("-")[1]
    labels.setdefault(who, {}).update(json.load(open(f)))
annotators = sorted(labels)
if len(annotators) < 2:
    sys.exit("need two blind annotators, found %s" % annotators)


def norm(v):
    return (v or "uncertain").strip().lower()


def kappa(a, b):
    n = len(a)
    if n == 0:
        return float("nan"), float("nan")
    po = sum(x == y for x, y in zip(a, b)) / n
    ca, cb = Counter(a), Counter(b)
    pe = sum(ca[k] * cb[k] for k in set(ca) | set(cb)) / (n * n)
    k = (po - pe) / (1 - pe) if pe < 1 else 1.0
    return k, 2 * po - 1  # Cohen's kappa, PABAK (two-category form; reported as a prevalence check)


def same_phrase(x, y):
    x, y = x.lower().strip(), y.lower().strip()
    return bool(x) and bool(y) and (x in y or y in x)


def answer_cat(g):
    a = g.get("answer", {})
    return (bool(a.get("mustNotStateAsCurrent")), tuple(sorted(set(a.get("mustNotLabel", [])))))


fields = {"replaces": {}, "sameProperty": {}, "status": {}, "current": {}, "answer": {}}
for f in fields:
    for w in ["author"] + annotators:
        fields[f][w] = {}
gold, disagreements = {}, []
for c in cases:
    cid = c["id"]
    L = {"author": c["authorGold"]}
    for w in annotators:
        if cid in labels[w]:
            L[w] = labels[w][cid]
    if len(L) < 3:
        disagreements.append(f"{cid}: missing labels from {set(['author'] + annotators) - set(L)}")
    invalid_votes = sum(1 for g in L.values() if g.get("invalid"))
    out = {"pairs": [], "status": {}, "current": [], "notCurrent": [], "answer": {}}
    if invalid_votes >= 2:
        gold[cid] = {"invalid": True, "reason": "; ".join(g.get("reason", "") for g in L.values() if g.get("invalid"))}
        continue
    # pairs
    keys = {(p["later"], p["earlier"]) for p in c["authorGold"]["pairs"]}
    for (la, ea) in sorted(keys):
        rec = {"later": la, "earlier": ea}
        for fld in ("sameProperty", "replaces"):
            votes = {}
            for w, g in L.items():
                v = next((norm(p.get(fld)) for p in g.get("pairs", []) if p.get("later") == la and p.get("earlier") == ea), "uncertain")
                votes[w] = v
                fields[fld][w][(cid, la, ea)] = v
            top, n = Counter(votes.values()).most_common(1)[0]
            rec[fld] = top if n >= 2 else "uncertain"
            if len(set(votes.values())) > 1:
                disagreements.append(f"{cid} {la}>{ea} {fld}: {votes}")
        rec["evidence"] = next((p.get("evidence") for p in c["authorGold"]["pairs"] if p["later"] == la and p["earlier"] == ea), "")
        out["pairs"].append(rec)
    # status and current
    for it in c["items"]:
        mid = it["mid"]
        votes = {w: g.get("status", {}).get(mid, "ATTRIBUTED_STATEMENT") for w, g in L.items()}
        for w, v in votes.items():
            fields["status"][w][(cid, mid)] = v
        top, n = Counter(votes.values()).most_common(1)[0]
        out["status"][mid] = top if n >= 2 else min(votes.values(), key=lambda s: STRENGTH.get(s, 0))
        if len(set(votes.values())) > 1:
            disagreements.append(f"{cid} {mid} status: {votes}")
        cur = {w: (mid in g.get("current", [])) for w, g in L.items()}
        for w, v in cur.items():
            fields["current"][w][(cid, mid)] = v
        (out["current"] if sum(cur.values()) >= 2 else out["notCurrent"]).append(mid)
    # answer
    for w, g in L.items():
        fields["answer"][w][cid] = answer_cat(g)
    for key in ("mustState", "mustNotStateAsCurrent", "mustNotLabel"):
        pool = [(w, p) for w, g in L.items() for p in g.get("answer", {}).get(key, [])]
        kept = []
        for w, p in pool:
            support = {w2 for w2, p2 in pool if same_phrase(p, p2)}
            if len(support) >= 2 and not any(same_phrase(p, k) for k in kept):
                kept.append(p)
        out["answer"][key] = kept
    gold[cid] = out

json.dump(gold, open(os.path.join(ROOT, "gold-adjudicated.json"), "w"), indent=1, ensure_ascii=False)

lines = [f"# Agreement ({SET}): annotators {annotators} + author\n",
         "| field | pair | n | Cohen's kappa | PABAK |", "|---|---|---|---|---|"]
pairs_to_report = [(annotators[0], annotators[1])] + [("author", w) for w in annotators]
for fld, by in fields.items():
    for a, b in pairs_to_report:
        common = sorted(set(by[a]) & set(by[b]), key=str)
        k, pabak = kappa([str(by[a][x]) for x in common], [str(by[b][x]) for x in common])
        lines.append(f"| {fld} | {a} vs {b} | {len(common)} | {k:.3f} | {pabak:.3f} |")
lines.append(f"\nTargets (PREREGISTRATION section 7): replaces >= 0.75, sameProperty >= 0.70, status >= 0.75, answer >= 0.80 (pending approval).")
lines.append(f"\nInvalid cases: {sorted(k for k, v in gold.items() if v.get('invalid'))}")
lines.append(f"\nDisagreements ({len(disagreements)}), for adjudication review and operator's audit:\n")
lines += ["- " + d for d in disagreements]
open(os.path.join(ROOT, "agreement.md"), "w").write("\n".join(lines) + "\n")
print("\n".join(lines[:4 + len(fields) * len(pairs_to_report)]))
print("invalid:", sorted(k for k, v in gold.items() if v.get("invalid")), "disagreements:", len(disagreements))
