"""Execute extracted blob JS with bounded mocks, not an Android/WebView acceptance test."""
from pathlib import Path
import json
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[4]
MANAGER = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/agent/browser/ported/browser/BrowserUseManager.kt'

HARNESS = r'''
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const script = fs.readFileSync(0, 'utf8');
async function check(kind) {
  let reads = 0, cancelled = 0, heldReject;
  const window = {};
  let finish;
  const completion = new Promise(resolve => { finish = resolve; });
  const reader = {
    read: async function() {
      reads++;
      if(kind === 'abort') return new Promise((resolve, reject) => { heldReject = reject; });
      if(kind === 'stream-limit') return {done:false, value:{byteLength:32*1024*1024 + 1}};
      if(kind === 'empty' || reads > 1) return {done:true};
      return {done:false, value:new Uint8Array([1, 2, 3])};
    },
    cancel: async function() { cancelled++; if(heldReject) heldReject(new Error('ABORTED')); }
  };
  class FileReader {
    constructor() { this.readyState = 0; }
    readAsDataURL(blob) {
      this.readyState = 1;
      blob.arrayBuffer().then(bytes => {
        if(this.readyState !== 1) return;
        this.result = 'data:application/octet-stream;base64,' + Buffer.from(bytes).toString('base64');
        this.readyState = 2;
        this.onload();
      });
    }
    abort() { this.readyState = 2; if(this.onabort) this.onabort(); }
  }
  const context = vm.createContext({
    window, AbortController, Blob, FileReader,
    fetch: async () => ({headers:{get: name => name === 'content-length' && kind === 'header-limit' ? String(32*1024*1024 + 1) : null}, body:{getReader:() => reader}}),
    __eta_child__: {
      resolve: (token, value) => { assert.equal(token, 'test_reply'); finish({ok:true, value}); },
      reject: (token, value) => { assert.equal(token, 'test_reply'); finish({ok:false, value}); }
    }
  });
  vm.runInContext(script, context, {timeout:1000});
  if(kind === 'abort') {
    await new Promise(resolve => setImmediate(resolve));
    assert(window.__eta_test_abort);
    window.__eta_test_abort.abort();
  }
  let timer;
  let result;
  try {
    result = await Promise.race([completion, new Promise((resolve, reject) => { timer = setTimeout(() => reject(new Error('no reply')), 1000); })]);
  } finally { clearTimeout(timer); }
  assert.equal(window.__eta_test_abort, undefined, 'abort hook removed in finally');
  if(kind === 'ok') { assert(result.ok); assert(result.value.endsWith('AQID')); assert.equal(reads, 2); }
  else if(kind === 'empty') { assert(result.ok); assert(result.value.endsWith('base64,')); }
  else { assert.equal(result.ok, false); assert.equal(result.value, 'CHILD_DOWNLOAD_FAILED'); }
  if(kind === 'header-limit') assert.equal(reads, 0, 'size header rejected before reading');
  if(kind === 'stream-limit' || kind === 'abort') assert(cancelled > 0, 'stream cancelled');
}
(async () => {
  for(const kind of ['ok', 'empty', 'header-limit', 'stream-limit', 'abort']) await check(kind);
  console.log('5 blob-script scenarios passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
'''


class ChildBrowserBlobScriptTest(unittest.TestCase):
    def test_actual_blob_script_success_empty_limits_and_abort(self):
        node = shutil.which('node')
        if node is None:
            self.skipTest('Node.js unavailable; Android/WebView tests still required')
        source = MANAGER.read_text().split('fun fetchChildBlobDownload(', 1)[1]
        script = source.split('val js = """', 1)[1].split('""".trimIndent()', 1)[0]
        script = script.replace('${JSONObject.quote(blobUrl)}', json.dumps('blob:https://example.test/id'))
        script = script.replace('$quotedAbortKey', json.dumps('__eta_test_abort'))
        script = script.replace('$replyKey', json.dumps('test_reply'))
        self.assertNotIn('$', script, 'all Kotlin interpolations must be replaced')
        result = subprocess.run([node, '-e', HARNESS], input=script, text=True, capture_output=True, timeout=15, cwd=ROOT)
        self.assertEqual(0, result.returncode, result.stderr[:2000])
        self.assertIn('5 blob-script scenarios passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
