#!/usr/bin/env python3
"""TASK-054 G1: build (never deploy) the Vespa package with local semantic retrieval for RAW MESSAGES.

Reuses scripts/task049-package.py unchanged: same approved bge-small-en-v1.5 manifest and hash checks, same
"dita-english" hugging-face-embedder, same derived knowledge embedding. Then adds to message.sd, without touching the
existing 8-D placeholder field (non-destructive, additive):
  - field text_embedding tensor<float>(x[384]), derived in Vespa from the message text (input text | embed dita-english)
  - rank profile "dense" (relevance = cosine, prenormalized-angular)

No downloads. No network. Usage:
  python3 scripts/task054-message-embedding-package.py APPROVED_MANIFEST.json NEW_OUTPUT_DIRECTORY
The manifest format is task049's (model_path/tokenizer_path relative to the manifest, approved SHA-256 values).
"""
import importlib.util, json, pathlib, sys

HERE = pathlib.Path(__file__).resolve().parent
MESSAGE_FIELD = '''    field text_embedding type tensor<float>(x[384]) {
        indexing: input text | embed dita-english | attribute | index
        attribute { distance-metric: prenormalized-angular }
    }
    rank-profile dense {
        inputs { query(q) tensor<float>(x[384]) }
        first-phase { expression: 1 - distance(field, text_embedding) }
        match-features: distance(field, text_embedding)
    }
'''

def load_task049():
    spec = importlib.util.spec_from_file_location("task049_package", HERE / "task049-package.py")
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    return mod

def patch_message_schema(out: pathlib.Path):
    schema = out / "schemas" / "message.sd"
    text = schema.read_text()
    if "text_embedding" in text: raise ValueError("message.sd already has text_embedding")
    pos = text.index("    fieldset default")           # outside the document block: derived, cannot be fed directly
    schema.write_text(text[:pos] + MESSAGE_FIELD + text[pos:])

def build(manifest, output):
    m = json.loads(pathlib.Path(manifest).read_text())
    if m.get("vespa_host") != "vespa-container":
        raise ValueError("demo-stack package: manifest vespa_host must be 'vespa-container' (docker/compose.yml hostname)")
    load_task049().build(manifest, output)                # hash-verified copy + embedder + knowledge embedding
    out = pathlib.Path(output)
    patch_message_schema(out)
    freeze = json.loads((out / "artifact-freeze.json").read_text())
    freeze["task054_message_embedding"] = {"field": "text_embedding", "rank_profile": "dense", "schema": "message",
                                           "placeholder_field_untouched": "embedding tensor<float>(x[8])"}
    (out / "artifact-freeze.json").write_text(json.dumps(freeze, indent=2) + "\n")
    print(json.dumps({"package": str(out), "message_field": "text_embedding"}))

if __name__ == "__main__":
    if len(sys.argv) != 3: sys.exit(__doc__)
    build(*sys.argv[1:])
