#!/usr/bin/env python3
"""Build, never deploy, a hybrid Vespa package from approved local artifacts. No downloads.
Usage: python3 scripts/task049-package.py APPROVED_MANIFEST.json NEW_OUTPUT_DIRECTORY
Manifest fields: approval_reference, model_id (BAAI/bge-small-en-v1.5), revision,
model_path, model_sha256, tokenizer_path, tokenizer_sha256. Paths relative to manifest.
Hashes must come from the approved freeze, not be generated to bless unknown files.
"""
import hashlib,json,pathlib,shutil,sys,xml.etree.ElementTree as ET

def build(manifest_path, output):
    manifest_path=pathlib.Path(manifest_path).resolve(); m=json.loads(manifest_path.read_text())
    if not m.get('approval_reference') or not m.get('revision') or m.get('model_id')!='BAAI/bge-small-en-v1.5':
        raise ValueError('approved immutable bge-small-en-v1.5 manifest required')
    artifacts={}
    for key in ('model','tokenizer'):
        p=(manifest_path.parent/m[key+'_path']).resolve(); expected=m[key+'_sha256']
        if len(expected)!=64 or hashlib.sha256(p.read_bytes()).hexdigest()!=expected:
            raise ValueError(key+' hash does not match approved freeze')
        artifacts[key]=p
    output=pathlib.Path(output)
    if output.exists(): raise ValueError('output must be a new directory')
    source=pathlib.Path(__file__).resolve().parents[1]/'vespa/app'
    shutil.copytree(source,output)
    models=output/'models'; models.mkdir()
    shutil.copyfile(artifacts['model'],models/'model.onnx'); shutil.copyfile(artifacts['tokenizer'],models/'tokenizer.json')
    services=ET.parse(output/'services.xml'); container=services.getroot().find('container')
    component=ET.SubElement(container,'component',{'id':'dita-english','type':'hugging-face-embedder'})
    ET.SubElement(component,'transformer-model',{'path':'models/model.onnx'})
    ET.SubElement(component,'tokenizer-model',{'path':'models/tokenizer.json'})
    # BERT ONNX consumes token_type_ids; keep Vespa's default input name.
    ET.SubElement(component,'normalize').text='true'
    ET.SubElement(component,'pooling-strategy').text='cls'
    ET.SubElement(component,'max-tokens').text='512'
    prepend=ET.SubElement(component,'prepend'); ET.SubElement(prepend,'query').text='Represent this sentence for searching relevant passages: '; ET.SubElement(prepend,'document').text=''
    services.write(output/'services.xml',encoding='utf-8',xml_declaration=True)
    hosts=ET.parse(output/'hosts.xml'); hosts.getroot().find('host').set('name',m.get('vespa_host','vespa-container'))
    hosts.write(output/'hosts.xml',encoding='utf-8',xml_declaration=True)
    schema=output/'schemas/knowledge.sd'; text=schema.read_text()
    # Derived field outside document: a caller cannot feed arbitrary vectors directly.
    pos=text.index('    fieldset default')
    text=text[:pos]+'''    field embedding type tensor<float>(x[384]) {
        indexing: input text | embed dita-english | attribute | index
        attribute { distance-metric: prenormalized-angular }
    }
    rank-profile dense {
        inputs { query(q) tensor<float>(x[384]) }
        first-phase { expression: 1 - distance(field, embedding) }
        match-features: distance(field, embedding)
    }
    rank-profile inspect inherits dense {
        summary-features: attribute(embedding)
    }
'''+text[pos:]
    schema.write_text(text)
    # Preserve approved identity, not host paths, in the deployable package.
    public={k:v for k,v in m.items() if not k.endswith('_path')}
    (output/'artifact-freeze.json').write_text(json.dumps(public,indent=2)+'\n')
    print(json.dumps({'package':str(output),'model_sha256':m['model_sha256'],'tokenizer_sha256':m['tokenizer_sha256']}))
if __name__=='__main__': build(*sys.argv[1:])
