#!/usr/bin/env python3
"""Package mechanics only: dummy bytes are NEVER loaded as a model or used for scoring."""
import hashlib,importlib.util,json,pathlib,tempfile,unittest
spec=importlib.util.spec_from_file_location('package049',pathlib.Path(__file__).with_name('task049-package.py'))
package=importlib.util.module_from_spec(spec);spec.loader.exec_module(package)
class PackageTest(unittest.TestCase):
 def test_approved_hashes_and_local_paths(self):
  with tempfile.TemporaryDirectory() as d:
   root=pathlib.Path(d);(root/'model').write_bytes(b'unit-test-not-a-model');(root/'tokenizer').write_bytes(b'unit-test-not-a-tokenizer')
   m={'approval_reference':'UNIT-TEST-ONLY','model_id':'BAAI/bge-small-en-v1.5','revision':'unit-test',
      'model_path':'model','tokenizer_path':'tokenizer','model_sha256':hashlib.sha256((root/'model').read_bytes()).hexdigest(),
      'tokenizer_sha256':hashlib.sha256((root/'tokenizer').read_bytes()).hexdigest()}
   path=root/'manifest.json';path.write_text(json.dumps(m));package.build(path,root/'out')
   self.assertIn('tensor<float>(x[384])',(root/'out/schemas/knowledge.sd').read_text())
   self.assertNotIn('http',(root/'out/services.xml').read_text())
   self.assertNotIn('model_path',json.loads((root/'out/artifact-freeze.json').read_text()))
   (root/'model').write_bytes(b'changed');
   with self.assertRaisesRegex(ValueError,'hash'):package.build(path,root/'bad')
   self.assertFalse((root/'bad').exists())
if __name__=='__main__':unittest.main()
