#!/usr/bin/env python3
"""TASK-044: per-question A/B/C comparison of user-facing answers (what changed between B and C).
Usage: python3 -I eval/synthetic-lab/compare.py <tag> [lab|holdout]
  reads <results>/answers-lab-{A,B,C}-<tag>.jsonl, runs score.py's checks via import, prints a Markdown report."""
import json, subprocess, sys

tag = sys.argv[1]; dataset = sys.argv[2] if len(sys.argv) > 2 else "lab"
results = {"lab": "eval/synthetic-lab/results", "holdout": "eval/synthetic-holdout/results",
           "holdout-v2": "eval/synthetic-holdout-v2/results"}[dataset]

def score(cfg):
    out = subprocess.run([sys.executable, "-I", "eval/synthetic-lab/score.py", f"lab-{cfg}-{tag}", dataset],
                         capture_output=True, text=True, check=True).stdout
    rows = {}
    for line in out.splitlines():
        if line.startswith("| ") and line[2:3].isdigit():
            c = [x.strip() for x in line.strip("|").split("|")]
            rows[int(c[0])] = {"auto": c[8], "user": c[9], "temporal": c[5], "stale": c[11]}
    return rows, out.strip().splitlines()[-1]

def answers(cfg):
    return {a["q"]: a for a in (json.loads(l) for l in open(f"{results}/answers-lab-{cfg}-{tag}.jsonl") if l.strip())}

S = {c: score(c) for c in "ABC"}
A = {c: answers(c) for c in "ABC"}
print(f"# A/B/C comparison ({dataset}, {tag})\n")
for c in "ABC": print(f"- **{c}**: {S[c][1].replace('**Totals:** ', '')}")
print("\n| q | question | A user-facing | B user-facing | C user-facing | C knowledge items used | B→C | C latency µs |")
print("|---|---|---|---|---|---|---|---|")
for q in sorted(A["C"]):
    b, c = S["B"][0][q]["user"], S["C"][0][q]["user"]
    change = "improved" if (b != "yes" and c == "yes") else "regressed" if (b == "yes" and c != "yes") else "same"
    print(f"| {q} | {A['C'][q]['question']} | {S['A'][0][q]['user']} | {b} | {c} | {A['C'][q]['knowledge_items_used']} | {change} | {A['C'][q].get('ask_micros', '-')} |")
print("\n## User-facing answers, B vs C (verbatim)\n")
for q in sorted(A["C"]):
    print(f"### Q{q}. {A['C'][q]['question']}\n")
    for cfg in "BC":
        a = A[cfg][q]
        cites = " ".join(str(x["message_id"]) for x in a["citations"])
        print(f"**{cfg}** ({a['behavior']}; cites {cites or '-'}):\n```\n{a['answer_text']}\n```")
    print()
